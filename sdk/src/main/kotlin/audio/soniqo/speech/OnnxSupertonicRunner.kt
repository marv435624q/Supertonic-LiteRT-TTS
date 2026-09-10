package audio.soniqo.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * End-to-end runner shared by the FP32 and W8A16 Supertonic-3 ONNX variants.
 *
 * IMPORTANT: this model deliberately does NOT use the native LiteRT orchestration.
 * The official ONNX reference uses INT64 text ids and dynamic text/latent lengths,
 * while the native LiteRT path is optimized around a fixed padded text shape and
 * a fixed diagnostic latent length. Routing the ONNX family through that path was
 * both incorrect and the reason the previous "Original" entry could create ORT
 * sessions without ever becoming a usable synthesizer.
 *
 * CPU: ORT CPU, following the official Supertonic-3 ONNX data flow.
 * Qualcomm NPU: modern Snapdragon devices keep the verified ORT QNN HTP +
 * ORT CPU hybrid path. SM6350/lito uses a separate QNN HTA compatibility probe.
 * Direct QAIRT 2.44 qnn-net-run + libQnnHta.so execution was validated on the
 * target device. REV30 keeps the REV28 probe behavior (CPU EP fallback available,
 * ORT graph optimization disabled, HTA context cache disabled) but replaces the
 * Maven ORT with a custom ORT 1.28.0 AAR that maps QNN_BACKEND_ID_HTA (7) to
 * QnnBackendType::HTA and includes HTA in IsNpuBackend(). This isolates whether
 * the previous CPU-backend misclassification caused invalid partitioning.
 */
internal class OnnxSupertonicRunner(
    private val config: SpeechSynthesizerConfig,
) : AutoCloseable {
    companion object {
        private const val TAG = "OriginalOnnx"
        private const val TEXT_EMBED_CHANNELS = 256
        private const val DEFAULT_SAMPLE_RATE = 44_100
        private const val DEFAULT_BASE_CHUNK_SIZE = 512
        private const val DEFAULT_CHUNK_COMPRESS_FACTOR = 6
        private const val DEFAULT_LATENT_DIM = 24
        private const val SILENCE_SECONDS = 0.3f
        private const val QNN_CONTEXT_CACHE_SCHEMA = 5
        private const val QNN_RUNTIME_TAG = "qnn2_44_ort1_28"
        private val QNN_TEXT_BUCKETS = intArrayOf(32, 48, 64, 80, 96, 112, 128)
        private val QNN_LATENT_BUCKETS = intArrayOf(32, 48, 64, 80, 96, 112, 128)

        // REV33.7: restore the exact pre-REV31 ONNX CPU execution model.
        // Despite the historical CPU_XNNPACK enum name, REV30 ONNX CPU sessions used
        // ORT's default CPU EP with the selected intra-op thread count. REV31 was the
        // first revision that actually registered XNNPACK for these ONNX graphs, and
        // that provider change regressed Original FP32 and W8A16 CPU performance.
        // LiteRT native XNNPACK and Qualcomm QNN/HTP/HTA paths are intentionally untouched.

        internal fun expectedQnnContextCount(model: TtsModel): Int = when (model) {
            TtsModel.SUPERTONIC_ORIGINAL_ONNX ->
                QNN_TEXT_BUCKETS.size + QNN_TEXT_BUCKETS.size +
                    (QNN_TEXT_BUCKETS.size * QNN_LATENT_BUCKETS.size) + QNN_LATENT_BUCKETS.size
            TtsModel.SUPERTONIC_ONNX_W8A16_QDQ ->
                QNN_TEXT_BUCKETS.size +
                    (QNN_TEXT_BUCKETS.size * QNN_LATENT_BUCKETS.size) + QNN_LATENT_BUCKETS.size
            TtsModel.SUPERTONIC,
            TtsModel.SUPERTONIC_LITERT_WI8_AFP32,
            TtsModel.SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU,
            TtsModel.SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU_WI8_AFP32 -> 0
        }

        private fun isSm6350HtaDevice(): Boolean {
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
            val haystack = listOf(
                soc, Build.BOARD, Build.HARDWARE, Build.DEVICE, Build.PRODUCT,
            ).joinToString(" ").lowercase(Locale.US)
            return haystack.contains("sm6350") || Regex("\\blito\\b").containsMatchIn(haystack)
        }

        private val AVAILABLE_LANGS = setOf(
            "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et",
            "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl",
            "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi", "na",
        )
    }

    private data class ModelConfig(
        val sampleRate: Int,
        val baseChunkSize: Int,
        val chunkCompressFactor: Int,
        val latentDim: Int,
    ) {
        val latentChannels: Int get() = latentDim * chunkCompressFactor
        val chunkSamples: Int get() = baseChunkSize * chunkCompressFactor
    }

    private data class VoiceStyle(
        val ttl: FloatArray,
        val ttlShape: LongArray,
        val dp: FloatArray,
        val dpShape: LongArray,
    )

    private data class TextInput(
        val ids: LongArray,
        val mask: FloatArray,
    ) {
        val length: Int get() = ids.size
    }

    private val env = OrtEnvironment.getEnvironment()
    private val stopped = AtomicBoolean(false)
    private val streamingActive = AtomicBoolean(false)
    private val rng = Random()
    private val modelConfig = readModelConfig()
    private val variantName: String = when (config.ttsModel) {
        TtsModel.SUPERTONIC_ORIGINAL_ONNX -> "Supertonic-3 ONNX FP32"
        TtsModel.SUPERTONIC_ONNX_W8A16_QDQ -> "Supertonic-3 ONNX W8A16 QDQ"
        TtsModel.SUPERTONIC,
        TtsModel.SUPERTONIC_LITERT_WI8_AFP32,
        TtsModel.SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU,
        TtsModel.SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU_WI8_AFP32 ->
            error("LiteRT model cannot use OnnxSupertonicRunner")
    }
    private val fixedTextT = QNN_TEXT_BUCKETS.last()
    private val fixedLatentL = QNN_LATENT_BUCKETS.last()
    private val fullTensorDiagnostics: Boolean
        get() = Log.isLoggable(TAG, Log.VERBOSE)
    val sampleRate: Int get() = modelConfig.sampleRate

    @Volatile private var profile = ""
    @Volatile private var w8a16DurationRecoveries = 0
    @Volatile private var w8a16DurationLastNpu = Float.NaN
    @Volatile private var w8a16DurationLastCpuActual = Float.NaN
    @Volatile private var w8a16DurationLastCpuPadded = Float.NaN
    @Volatile private var styleCacheId: String? = null
    @Volatile private var styleCache: VoiceStyle? = null
    private val pregenRunners = mutableListOf<OnnxSupertonicRunner>()
    private val deepProfileSequence = AtomicInteger(0)
    private val deepProfileRunId = System.currentTimeMillis().toString()
    private val deepProfileSessions = mutableListOf<GraphSession>()
    private fun deepProfileRoot(): File = File(config.acceleratorCacheDir, "perf_profiles").apply { mkdirs() }
    // Diagnostic QNN contexts can be very large; keep them out of the pull directory.
    private fun deepProfileContextRoot(): File = File(config.acceleratorCacheDir, "perf_profile_contexts").apply { mkdirs() }
    private fun safeProfileName(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    private fun nextOrtProfilePrefix(graphName: String): String {
        val seq = deepProfileSequence.incrementAndGet()
        return File(
            deepProfileRoot(),
            "ort_${deepProfileRunId}_${safeProfileName(config.ttsModel.name)}_${safeProfileName(config.backend.name)}_${safeProfileName(graphName)}_$seq",
        ).absolutePath
    }
    private fun nextQnnProfileFile(graphName: String): File {
        val seq = deepProfileSequence.incrementAndGet()
        return File(
            deepProfileRoot(),
            "qnn_${deepProfileRunId}_${safeProfileName(config.ttsModel.name)}_${safeProfileName(graphName)}_$seq.csv",
        )
    }
    private fun qnnProfilerContextFile(model: File, graphName: String, shapeKey: String): File {
        val stamp = "${model.length()}_${model.lastModified()}"
        return File(
            deepProfileContextRoot(),
            "qnnctx_prof_${safeProfileName(config.ttsModel.name)}_${safeProfileName(graphName)}_${safeProfileName(shapeKey)}_${stamp}.onnx",
        )
    }
    private val useSm6350Hta: Boolean =
        config.backend == InferenceBackend.QUALCOMM_NPU && isSm6350HtaDevice()

    init {
        if (config.enableDeepProfiler) {
            Log.w(TAG, "[DEEP-PROFILER] enabled=1 one_shot=1 dir=${deepProfileRoot().absolutePath} timing_is_diagnostic=1")
        }
        if (config.backend == InferenceBackend.QUALCOMM_NPU) {
            if (useSm6350Hta) {
                val nativeDir = File(config.nativeLibraryDir)
                Log.i(
                    TAG,
                    "[QNN-HTA-PROBE] device=SM6350/lito backend=libQnnHta.so " +
                        "cpuFallback=1 contextCache=0 ortOpt=NO_OPT",
                )
                Log.i(
                    TAG,
                    "[QNN-HTA-LIBS] nativeDir=${nativeDir.absolutePath} " +
                        "hta=${File(nativeDir, "libQnnHta.so").isFile} " +
                        "runtime=${File(nativeDir, "libhta_hexagon_runtime_qnn.so").isFile} " +
                        "system=${File(nativeDir, "libQnnSystem.so").isFile}",
                )
            } else {
                cleanupStaleQnnContextCaches()
            }
            Log.i(
                TAG,
                "[BUCKET-GRID] T=${QNN_TEXT_BUCKETS.joinToString("/")} " +
                    "L=${QNN_LATENT_BUCKETS.joinToString("/")} combinations=${QNN_TEXT_BUCKETS.size * QNN_LATENT_BUCKETS.size}",
            )
        }
    }

    private fun qnnModelCacheKey(): String = when (config.ttsModel) {
        TtsModel.SUPERTONIC_ORIGINAL_ONNX -> "fp32"
        TtsModel.SUPERTONIC_ONNX_W8A16_QDQ -> "w8a16"
        TtsModel.SUPERTONIC,
        TtsModel.SUPERTONIC_LITERT_WI8_AFP32,
        TtsModel.SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU,
        TtsModel.SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU_WI8_AFP32 ->
            error("LiteRT has no QNN ONNX context cache")
    }

    private fun qnnUnifiedCacheRoot(): File {
        val filesRoot = File(config.modelDir).parentFile ?: File(config.modelDir)
        return File(filesRoot, "supertonic-3-qnn-context-cache")
    }

    private fun qnnRuntimeCacheRoot(): File =
        File(qnnUnifiedCacheRoot(), "${QNN_RUNTIME_TAG}_v${QNN_CONTEXT_CACHE_SCHEMA}")

    private fun qnnModelCacheRoot(): File =
        File(qnnRuntimeCacheRoot(), qnnModelCacheKey()).apply { mkdirs() }

    private fun migrateLegacyModelLocalQnnContextCache() {
        val legacyRoot = File(config.modelDir, ".qnn_context_cache")
        if (!legacyRoot.isDirectory) return
        // REV24 used schema v3 with the same HTP options. Only migrate that
        // compatible directory; older v1/v2 contexts were compiled with different
        // provider settings and must not be reused.
        val compatibleLegacy = File(legacyRoot, "${QNN_RUNTIME_TAG}_v3")
        if (!compatibleLegacy.isDirectory) {
            runCatching { legacyRoot.deleteRecursively() }
            return
        }
        val target = qnnModelCacheRoot()
        var movedFiles = 0
        var movedBytes = 0L
        var allMigrated = true
        compatibleLegacy.walkTopDown().filter { it.isFile && it.name.endsWith("_ctx.onnx") }.forEach { src ->
            val dst = File(target, src.name)
            if (!dst.exists()) {
                val bytes = src.length()
                val copied = runCatching { src.copyTo(dst, overwrite = false); true }.getOrDefault(false)
                if (copied) {
                    movedFiles++
                    movedBytes += bytes
                } else {
                    allMigrated = false
                    Log.w(TAG, "[QNN-CONTEXT-MIGRATE] copy failed source=${src.absolutePath}")
                }
            }
        }
        if (movedFiles > 0) {
            Log.i(TAG, "[QNN-CONTEXT-MIGRATE] model=${qnnModelCacheKey()} files=$movedFiles bytes=$movedBytes")
        }
        if (allMigrated) runCatching { legacyRoot.deleteRecursively() }
    }

    private fun cleanupStaleQnnContextCaches() {
        migrateLegacyModelLocalQnnContextCache()
        val root = qnnUnifiedCacheRoot()
        if (!root.isDirectory) return
        val keep = "${QNN_RUNTIME_TAG}_v${QNN_CONTEXT_CACHE_SCHEMA}"
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("${QNN_RUNTIME_TAG}_v") && it.name != keep }
            ?.forEach { stale ->
                val bytes = stale.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (runCatching { stale.deleteRecursively() }.getOrDefault(false)) {
                    Log.i(TAG, "[QNN-CONTEXT-CLEANUP] removed=${stale.name} bytes=$bytes")
                } else {
                    Log.w(TAG, "[QNN-CONTEXT-CLEANUP] failed=${stale.absolutePath}")
                }
            }
    }

    private val unicodeIndexer: LongArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val file = File(config.modelDir, "unicode_indexer.json")
        require(file.isFile) { "$variantName unicode_indexer.json missing: ${file.absolutePath}" }
        // Upstream helper.py loads this JSON as a Python list and indexes it with
        // np.uint16 Unicode values: self.indexer[val]. Mirror that literally.
        val json = JSONArray(file.readText())
        LongArray(json.length()) { i -> json.getLong(i) }
    }

    private fun cpuThreadsForGraph(graphName: String): Int {
        val maxThreads = config.numThreads.coerceIn(1, 64)
        val lower = graphName.lowercase(Locale.US)
        return when {
            "duration" in lower -> minOf(2, maxThreads)
            "encoder" in lower -> minOf(4, maxThreads)
            else -> maxThreads // vector estimator / vocoder: dominant CPU stages
        }
    }

    private fun newSessionOptions(
        graphName: String,
        symbolicOverrides: Map<String, Long> = emptyMap(),
        qnnContextOutput: File? = null,
        optimizationOverride: OrtSession.SessionOptions.OptLevel? = null,
    ): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
            when (config.backend) {
                InferenceBackend.CPU_ORT,
                InferenceBackend.CPU_XNNPACK, // legacy in-memory compatibility
                InferenceBackend.CPU_XNNPACK_FP16 -> {
                    val cpuThreads = config.numThreads.coerceIn(1, 64)
                    setIntraOpNumThreads(cpuThreads)
                    setInterOpNumThreads(1)
                    if (BuildConfig.ORT_RUNTIME_VARIANT == "REV40_HTA_CPU_REF") {
                        // Exact CPU SessionOptions policy from the uploaded SM6350/HTA REV40 source:
                        // only intra/inter threads are set; arena and memory-pattern are left to ORT defaults.
                        Log.i(TAG, "[ORT-CPU-REV40-REF] graph=$graphName provider=CPU threads=$cpuThreads xnnpack_registered=0 exact_policy=1 arena=default mem_pattern=default")
                    } else {
                        setMemoryPatternOptimization(true)
                        setCPUArenaAllocator(true)
                        Log.i(TAG, "[ORT-CPU] graph=$graphName provider=CPU threads=$cpuThreads xnnpack_registered=0 per_session_pool=1 spin=default arena=1 mem_pattern=1")
                    }
                }
                InferenceBackend.ONNX_XNNPACK -> {
                    val providers = OrtEnvironment.getAvailableProviders()
                    require(providers.contains(OrtProvider.XNNPACK)) {
                        "Bundled ORT native core has no XNNPACK EP. providers=$providers. " +
                            "Run BUILD_CUSTOM_ORT_QNN_XNNPACK.bat and rebuild the APK."
                    }
                    val fallbackThreads = config.numThreads.coerceIn(1, 64)
                    val xnnThreads = cpuThreadsForGraph(graphName)
                    // Best measured explicit-XNN baseline so far (REV33.5): keep both
                    // pools multi-threaded and do not force ORT spin=0, which regressed.
                    setIntraOpNumThreads(fallbackThreads)
                    setInterOpNumThreads(1)
                    setMemoryPatternOptimization(true)
                    setCPUArenaAllocator(true)
                    addXnnpack(mapOf("intra_op_num_threads" to xnnThreads.toString()))
                    Log.i(
                        TAG,
                        "[ORT-XNNPACK] graph=$graphName registered=1 xnn_threads=$xnnThreads " +
                            "ort_fallback_threads=$fallbackThreads per_session_pool=1 spin=default arena=1 mem_pattern=1",
                    )
                }
                InferenceBackend.QUALCOMM_NPU -> {
                    setIntraOpNumThreads(config.numThreads.coerceIn(1, 64))
                    setInterOpNumThreads(1)
                }
            }

            val optLevel = if (useSm6350Hta && config.backend == InferenceBackend.QUALCOMM_NPU) {
                // ORT issue #26739: HTA does not support some layout Transpose nodes that ORT
                // can insert. Keep this compatibility probe as close to the source graph as possible.
                OrtSession.SessionOptions.OptLevel.NO_OPT
            } else {
                optimizationOverride ?: OrtSession.SessionOptions.OptLevel.ALL_OPT
            }
            setOptimizationLevel(optLevel)
            if (config.enableDeepProfiler) {
                val prefix = nextOrtProfilePrefix(graphName)
                enableProfiling(prefix)
                Log.i(TAG, "[ORT-PROFILER] graph=$graphName enabled=1 prefix=$prefix")
            }
            val optLabel = if (optLevel == OrtSession.SessionOptions.OptLevel.NO_OPT) "NO_OPT" else "ALL_OPT"
            Log.i(
                TAG,
                "[ORT-OPT] graph=$graphName level=$optLabel " +
                    "variant=${config.ttsModel.name} backend=${config.backend.name}",
            )

            symbolicOverrides.forEach { (name, value) ->
                setSymbolicDimensionValue(name, value)
                Log.i(TAG, "[FREE-DIM] graph=$graphName name=$name value=$value")
            }
            when (config.backend) {
                InferenceBackend.CPU_ORT,
                InferenceBackend.CPU_XNNPACK, // legacy in-memory compatibility
                InferenceBackend.CPU_XNNPACK_FP16,
                InferenceBackend.ONNX_XNNPACK -> Unit // CPU EP/XNNPACK were configured above.
                InferenceBackend.QUALCOMM_NPU -> {
                    val providers = OrtEnvironment.getAvailableProviders()
                    require(providers.contains(OrtProvider.QNN)) {
                        "Bundled ORT native core has no QNN EP. providers=$providers. " +
                            "Run BUILD_CUSTOM_ORT_QNN_XNNPACK.bat and rebuild the APK."
                    }
                    Log.i(TAG, "[ORT-PROVIDERS] required=QNN available=$providers verified=1")
                    val w8a16Qdq = config.ttsModel == TtsModel.SUPERTONIC_ONNX_W8A16_QDQ
                    setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
                    setSessionLogVerbosityLevel(0)
                    if (useSm6350Hta) {
                        // ORT 1.28 maps unknown QNN backend id 7 (HTA) to its internal CPU
                        // backend enum. REV26 therefore failed before graph capability probing when
                        // CPU EP fallback was disabled. Keep fallback available in this compatibility
                        // probe so the real HTA backend can reach op validation/finalization.
                        setLoggerId(
                            if (w8a16Qdq) "supertonic_${graphName}_qnn_hta_w8a16_compat"
                            else "supertonic_${graphName}_qnn_hta_compat"
                        )
                        setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
                        setSessionLogVerbosityLevel(0)
                        require(qnnContextOutput == null) {
                            "SM6350 HTA backend must not generate persistent QNN context"
                        }
                        addQnn(qnnOptions("libQnnHta.so", graphName, htp = false))
                        Log.i(
                            TAG,
                            "[HTA-SESSION] graph=$graphName QNN=HTA CPU=fallback-enabled " +
                                "context=disabled ortOpt=NO_OPT adaptiveBuckets=1",
                        )
                    } else {
                        // Existing modern Snapdragon HTP path is intentionally
                        // unchanged: QNN first, ORT CPU EP available for unsupported
                        // fragments, persistent context enabled per adaptive bucket.
                        setLoggerId(
                            if (w8a16Qdq) "supertonic_${graphName}_qnn_w8a16_qdq_hybrid"
                            else "supertonic_${graphName}_qnn_hybrid"
                        )
                        if (qnnContextOutput != null) {
                            addConfigEntry("ep.context_enable", "1")
                            addConfigEntry("ep.context_embed_mode", "1")
                            addConfigEntry("ep.context_file_path", qnnContextOutput.absolutePath)
                            Log.i(TAG, "[QNN-CONTEXT-GENERATE] graph=$graphName path=${qnnContextOutput.absolutePath}")
                        }
                        addQnn(qnnOptions("libQnnHtp.so", graphName, htp = true))
                        Log.i(
                            TAG,
                            "[HYBRID-SESSION] graph=$graphName QNN=priority1 CPU=fallback-enabled " +
                                "adaptiveBuckets=1",
                        )
                    }
                }
            }
        }

    private fun qnnOptions(
        library: String,
        graphName: String,
        htp: Boolean,
    ): Map<String, String> = linkedMapOf<String, String>().apply {
        put("backend_path", qnnLibrary(library))
        if (config.enableDeepProfiler) {
            val qnnProfile = nextQnnProfileFile(graphName)
            put("profiling_level", "optrace")
            put("profiling_file_path", qnnProfile.absolutePath)
            Log.i(TAG, "[QNN-PROFILER] graph=$graphName backend=$library level=optrace csv=${qnnProfile.absolutePath}")
        } else {
            put("profiling_level", "off")
        }
        if (htp) {
            put("htp_performance_mode", "burst")
            // REV24 runtime optimizations. Context-cache schema 3 keeps these
            // compiled graphs separate from older baseline contexts.
            put("enable_htp_shared_memory_allocator", "1")
            put("offload_graph_io_quantization", "0")
            put("htp_graph_finalization_optimization_mode", "3")
            // FP32 may use HTP FP16 precision internally; W8A16 keeps its QDQ contract.
            if (config.ttsModel == TtsModel.SUPERTONIC_ORIGINAL_ONNX) {
                put("enable_htp_fp16_precision", "1")
            }
        }
        Log.i(
            TAG,
            "[QNN-OPTIONS] graph=$graphName backend=$library profiling=${if (config.enableDeepProfiler) "optrace" else "off"} " +
                "htpFp16=${if (htp && config.ttsModel == TtsModel.SUPERTONIC_ORIGINAL_ONNX) 1 else 0} " +
                "sharedMem=${if (htp) 1 else 0} ioQdqOnQnn=${if (htp) 1 else 0} " +
                "finalizeMode=${if (htp) 3 else 0} " +
                "w8a16Qdq=${if (config.ttsModel == TtsModel.SUPERTONIC_ONNX_W8A16_QDQ) 1 else 0} " +
                "variant=${config.ttsModel.name}",
        )
    }

    private fun qnnLibrary(name: String): String =
        config.nativeLibraryDir.takeIf { it.isNotBlank() }
            ?.let { File(it, name).absolutePath }
            ?: name

    private fun qnnContextCacheFile(model: File, graphName: String, shapeKey: String): File {
        val sourceStamp = "${model.length()}_${model.lastModified()}"
        val dir = qnnModelCacheRoot()
        return File(dir, "${graphName}_${shapeKey}_${sourceStamp}_ctx.onnx")
    }

    private inner class GraphSession(
        fileName: String,
        private val graphName: String,
        symbolicOverrides: Map<String, Long> = emptyMap(),
        symbolicOverridesProvider: (() -> Map<String, Long>)? = null,
        persistQnnContext: Boolean = false,
        contextShapeKey: String = "generic",
        optimizationOverride: OrtSession.SessionOptions.OptLevel? = null,
    ) : AutoCloseable {
        val session: OrtSession
        private val inputNames: Set<String>
        val outputName: String
        val contextState: String
        @Volatile private var profilingEnded = false

        init {
            val model = File(config.modelDir, fileName)
            require(model.isFile) { "$variantName[$graphName] missing: ${model.absolutePath}" }
            val usePersistentContext =
                persistQnnContext &&
                    config.backend == InferenceBackend.QUALCOMM_NPU &&
                    !useSm6350Hta &&
                    // W8A16 duration is deliberately CPU-dynamic and must never
                    // create/use a QNN context. All other NPU graphs persist one
                    // compiled context per adaptive bucket.
                    !(config.ttsModel == TtsModel.SUPERTONIC_ONNX_W8A16_QDQ && graphName == "duration")
            val contextFile = if (usePersistentContext) {
                if (config.enableDeepProfiler) qnnProfilerContextFile(model, graphName, contextShapeKey)
                else qnnContextCacheFile(model, graphName, contextShapeKey)
            } else null
            if (persistQnnContext && config.backend == InferenceBackend.QUALCOMM_NPU &&
                config.ttsModel == TtsModel.SUPERTONIC_ONNX_W8A16_QDQ && graphName == "duration"
            ) {
                Log.i(TAG, "[W8A16-QNN-CONTEXT] graph=duration disabled reason=cpu_dynamic_duration")
            }
            val createStartNs = System.nanoTime()

            var created: OrtSession? = null
            var state = "disabled"

            // Fast path after the first successful on-device compile. The context
            // model contains the finalized QNN graph and can be opened as a normal
            // ONNX model. Keep CPU EP fallback enabled because the source session
            // is intentionally hybrid.
            if (contextFile != null && contextFile.isFile && contextFile.length() > 0L) {
                val loadOpts = newSessionOptions(graphName, optimizationOverride = optimizationOverride)
                try {
                    Log.i(
                        TAG,
                        "[QNN-CONTEXT-HIT] graph=$graphName bytes=${contextFile.length()} " +
                            "path=${contextFile.absolutePath}",
                    )
                    created = env.createSession(contextFile.absolutePath, loadOpts)
                    state = "hit"
                } catch (t: Throwable) {
                    // Cache compatibility can change after a model/QNN/ORT update.
                    // A bad cache must never brick TTS: delete it and rebuild once.
                    Log.w(
                        TAG,
                        "[QNN-CONTEXT-STALE] graph=$graphName type=${t.javaClass.simpleName} " +
                            "msg=${t.message}; deleting and recompiling",
                    )
                    runCatching { contextFile.delete() }
                } finally {
                    loadOpts.close()
                }
            }

            if (created == null) {
                // IMPORTANT: resolve symbolic dimensions only after the persistent
                // context fast-path has missed. REV9 callers discovered T/L before
                // constructing GraphSession, which reopened the full source ONNX
                // model even on a context hit and defeated much of the cache win.
                val resolvedOverrides = symbolicOverridesProvider?.invoke() ?: symbolicOverrides
                val compileOpts = newSessionOptions(
                    graphName = graphName,
                    symbolicOverrides = resolvedOverrides,
                    qnnContextOutput = contextFile,
                    optimizationOverride = optimizationOverride,
                )
                try {
                    Log.i(
                        TAG,
                        "[SESSION-CREATE] graph=$graphName backend=${config.backend.name} model=${model.name}",
                    )
                    created = env.createSession(model.absolutePath, compileOpts)
                    state = if (contextFile != null) {
                        if (contextFile.isFile && contextFile.length() > 0L) {
                            Log.i(
                                TAG,
                                "[QNN-CONTEXT-SAVED] graph=$graphName bytes=${contextFile.length()} " +
                                    "path=${contextFile.absolutePath}",
                            )
                            "generated"
                        } else {
                            Log.w(TAG, "[QNN-CONTEXT-NOT-WRITTEN] graph=$graphName")
                            "not_written"
                        }
                    } else {
                        "disabled"
                    }
                } catch (t: Throwable) {
                    Log.e(
                        TAG,
                        "[SESSION-FAIL] graph=$graphName backend=${config.backend.name} " +
                            "afterMs=${formatMs(elapsedMs(createStartNs))} type=${t.javaClass.simpleName} msg=${t.message}",
                        t,
                    )
                    throw t
                } finally {
                    compileOpts.close()
                }
            }

            session = checkNotNull(created)
            contextState = state
            inputNames = session.inputNames
            outputName = session.outputNames.firstOrNull()
                ?: error("Supertone Original[$graphName] has no output")
            Log.i(
                TAG,
                "[SESSION-READY] graph=$graphName backend=${config.backend.name} " +
                    "createMs=${formatMs(elapsedMs(createStartNs))} context=$contextState " +
                    "inputs=${session.inputNames} outputs=${session.outputNames}",
            )
            if (config.enableDeepProfiler) synchronized(deepProfileSessions) { deepProfileSessions += this }
        }

        fun input(semantic: String): String {
            if (semantic in inputNames) return semantic
            return inputNames.firstOrNull { it.endsWith(semantic) || it.contains(semantic) }
                ?: error(
                    "Supertone Original[$graphName] input '$semantic' missing; " +
                        "inputs=${inputNames.joinToString(",")}",
                )
        }

        fun finishProfiling() {
            if (!config.enableDeepProfiler || profilingEnded) return
            synchronized(this) {
                if (profilingEnded) return
                runCatching { session.endProfiling() }
                    .onSuccess { path -> Log.i(TAG, "[ORT-PROFILE-FILE] graph=$graphName path=$path") }
                    .onFailure { t -> Log.w(TAG, "[ORT-PROFILE-END-FAIL] graph=$graphName msg=${t.message}") }
                profilingEnded = true
            }
        }

        override fun close() {
            finishProfiling()
            synchronized(deepProfileSessions) { deepProfileSessions.remove(this) }
            session.close()
        }
    }

    private val w8a16DurationCpuSessionLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        require(config.ttsModel == TtsModel.SUPERTONIC_ONNX_W8A16_QDQ)
        val model = File(config.modelDir, "duration_predictor.onnx")
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(config.numThreads.coerceIn(1, 64))
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        try {
            Log.i(TAG, "[W8A16-DURATION-DIAG] creating CPU reference duration session")
            env.createSession(model.absolutePath, options)
        } finally {
            options.close()
        }
    }

    // REV24: CPU keeps the official dynamic sessions. Qualcomm NPU uses an
    // adaptive 4x4 bucket grid and persists one compiled context per bucket.
    // Only the currently-selected bucket is kept as a live OrtSession in RAM.
    private val durationDynamicLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GraphSession("duration_predictor.onnx", "duration")
    }
    private val encoderDynamicLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GraphSession("text_encoder.onnx", "encoder")
    }
    private val vectorDynamicLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GraphSession("vector_estimator.onnx", "vector_estimator")
    }
    private val vocoderDynamicLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GraphSession("vocoder.onnx", "vocoder")
    }

    @Volatile private var durationSpecializedT: Int? = null
    @Volatile private var durationSpecialized: GraphSession? = null
    @Volatile private var encoderSpecializedT: Int? = null
    @Volatile private var encoderSpecialized: GraphSession? = null
    private data class VectorShapeKey(val textT: Int, val latentL: Int)
    @Volatile private var vectorSpecializedKey: VectorShapeKey? = null
    @Volatile private var vectorSpecialized: GraphSession? = null
    @Volatile private var vocoderSpecializedL: Int? = null
    @Volatile private var vocoderSpecialized: GraphSession? = null

    private fun selectBucket(actual: Int, buckets: IntArray, axis: String): Int =
        buckets.firstOrNull { it >= actual }
            ?: error("No QNN $axis bucket can hold actual=$actual; max=${buckets.last()}")

    @Synchronized
    private fun durationSession(textT: Int): GraphSession {
        if (config.backend != InferenceBackend.QUALCOMM_NPU) return durationDynamicLazy.value
        require(config.ttsModel != TtsModel.SUPERTONIC_ONNX_W8A16_QDQ) {
            "W8A16 Qualcomm NPU duration is CPU-dynamic-only"
        }
        durationSpecialized?.takeIf { durationSpecializedT == textT }?.let { return it }
        durationSpecialized?.let { runCatching { it.close() } }
        return GraphSession(
            "duration_predictor.onnx",
            "duration",
            symbolicOverridesProvider = {
                discoverSymbolicOverrides(
                    fileName = "duration_predictor.onnx",
                    graphName = "duration",
                    concreteInputShapes = linkedMapOf(
                        "text_ids" to longArrayOf(1, textT.toLong()),
                        "style_dp" to null,
                    ),
                    concreteOutputShape = null,
                ).also { Log.i(TAG, "[STATIC-QNN] graph=duration textT=$textT overrides=$it") }
            },
            persistQnnContext = true,
            contextShapeKey = "t$textT",
        ).also {
            durationSpecializedT = textT
            durationSpecialized = it
        }
    }

    @Synchronized
    private fun encoderSession(textT: Int): GraphSession {
        if (config.backend != InferenceBackend.QUALCOMM_NPU) return encoderDynamicLazy.value
        encoderSpecialized?.takeIf { encoderSpecializedT == textT }?.let { return it }
        encoderSpecialized?.let { runCatching { it.close() } }
        return GraphSession(
            "text_encoder.onnx",
            "encoder",
            symbolicOverridesProvider = {
                discoverSymbolicOverrides(
                    fileName = "text_encoder.onnx",
                    graphName = "encoder",
                    concreteInputShapes = linkedMapOf(
                        "text_ids" to longArrayOf(1, textT.toLong()),
                        "style_ttl" to null,
                    ),
                    concreteOutputShape = longArrayOf(
                        1, TEXT_EMBED_CHANNELS.toLong(), textT.toLong(),
                    ),
                ).also { Log.i(TAG, "[STATIC-QNN] graph=encoder textT=$textT overrides=$it") }
            },
            persistQnnContext = true,
            contextShapeKey = "t$textT",
        ).also {
            encoderSpecializedT = textT
            encoderSpecialized = it
        }
    }

    @Synchronized
    private fun vectorSession(textT: Int, latentL: Int): GraphSession {
        if (config.backend != InferenceBackend.QUALCOMM_NPU) return vectorDynamicLazy.value
        val key = VectorShapeKey(textT, latentL)
        vectorSpecialized?.takeIf { vectorSpecializedKey == key }?.let { return it }
        vectorSpecialized?.let { runCatching { it.close() } }
        return GraphSession(
            "vector_estimator.onnx",
            "vector_estimator",
            symbolicOverridesProvider = {
                discoverSymbolicOverrides(
                    fileName = "vector_estimator.onnx",
                    graphName = "vector_estimator",
                    concreteInputShapes = linkedMapOf(
                        "noisy_latent" to longArrayOf(1, modelConfig.latentChannels.toLong(), latentL.toLong()),
                        "text_emb" to longArrayOf(1, TEXT_EMBED_CHANNELS.toLong(), textT.toLong()),
                        "style_ttl" to null,
                        "latent_mask" to longArrayOf(1, 1, latentL.toLong()),
                        "text_mask" to longArrayOf(1, 1, textT.toLong()),
                        "current_step" to longArrayOf(1),
                        "total_step" to longArrayOf(1),
                    ),
                    concreteOutputShape = longArrayOf(1, modelConfig.latentChannels.toLong(), latentL.toLong()),
                ).also {
                    Log.i(TAG, "[STATIC-QNN] graph=vector_estimator textT=$textT latentL=$latentL overrides=$it")
                }
            },
            persistQnnContext = true,
            contextShapeKey = "t${textT}_l${latentL}",
        ).also {
            vectorSpecializedKey = key
            vectorSpecialized = it
        }
    }

    @Synchronized
    private fun vocoderSession(latentL: Int): GraphSession {
        if (config.backend != InferenceBackend.QUALCOMM_NPU) return vocoderDynamicLazy.value
        vocoderSpecialized?.takeIf { vocoderSpecializedL == latentL }?.let { return it }
        vocoderSpecialized?.let { runCatching { it.close() } }
        return GraphSession(
            "vocoder.onnx",
            "vocoder",
            symbolicOverridesProvider = {
                discoverSymbolicOverrides(
                    fileName = "vocoder.onnx",
                    graphName = "vocoder",
                    concreteInputShapes = linkedMapOf(
                        "latent" to longArrayOf(1, modelConfig.latentChannels.toLong(), latentL.toLong()),
                    ),
                    concreteOutputShape = longArrayOf(1, (modelConfig.chunkSamples * latentL).toLong()),
                ).also { Log.i(TAG, "[STATIC-QNN] graph=vocoder latentL=$latentL overrides=$it") }
            },
            persistQnnContext = true,
            contextShapeKey = "l$latentL",
        ).also {
            vocoderSpecializedL = latentL
            vocoderSpecialized = it
        }
    }

    internal data class QnnCachePreGenProgress(
        val completed: Int,
        val total: Int,
        val graph: String,
        val shape: String,
        val contextState: String,
    )

    internal data class QnnCachePreGenSummary(
        val total: Int,
        val generated: Int,
        val hits: Int,
        val notWritten: Int,
    )

    internal fun preGenerateAllQnnContexts(
        onProgress: (QnnCachePreGenProgress) -> Unit,
    ): QnnCachePreGenSummary {
        require(config.backend == InferenceBackend.QUALCOMM_NPU) {
            "QNN context pre-generation requires Qualcomm NPU backend"
        }
        require(!useSm6350Hta) {
            "QNN context pre-generation is disabled for the SM6350 HTA backend"
        }
        stopped.set(false)
        val total = expectedQnnContextCount(config.ttsModel)
        var completed = 0
        var generated = 0
        var hits = 0
        var notWritten = 0

        fun visit(graph: String, shape: String, sessionFactory: () -> GraphSession) {
            ensureNotStopped()
            val session = sessionFactory()
            when (session.contextState) {
                "generated" -> generated++
                "hit" -> hits++
                else -> notWritten++
            }
            completed++
            Log.i(
                TAG,
                "[QNN-PREGEN] model=${qnnModelCacheKey()} $completed/$total graph=$graph shape=$shape state=${session.contextState}",
            )
            onProgress(
                QnnCachePreGenProgress(
                    completed = completed,
                    total = total,
                    graph = graph,
                    shape = shape,
                    contextState = session.contextState,
                )
            )
        }

        try {
            if (config.ttsModel == TtsModel.SUPERTONIC_ORIGINAL_ONNX) {
                for (t in QNN_TEXT_BUCKETS) visit("duration", "T$t") { durationSession(t) }
            }
            for (t in QNN_TEXT_BUCKETS) visit("encoder", "T$t") { encoderSession(t) }
            for (t in QNN_TEXT_BUCKETS) {
                for (l in QNN_LATENT_BUCKETS) {
                    visit("vector_estimator", "T$t/L$l") { vectorSession(t, l) }
                }
            }
            for (l in QNN_LATENT_BUCKETS) visit("vocoder", "L$l") { vocoderSession(l) }
        } finally {
            closeSpecializedQnnSessions()
        }

        Log.i(
            TAG,
            "[QNN-PREGEN-END] model=${qnnModelCacheKey()} total=$total generated=$generated hits=$hits notWritten=$notWritten",
        )
        return QnnCachePreGenSummary(total, generated, hits, notWritten)
    }

    private fun closeSpecializedQnnSessions() {
        durationSpecialized?.let { runCatching { it.close() } }
        encoderSpecialized?.let { runCatching { it.close() } }
        vectorSpecialized?.let { runCatching { it.close() } }
        vocoderSpecialized?.let { runCatching { it.close() } }
        durationSpecialized = null
        encoderSpecialized = null
        vectorSpecialized = null
        vocoderSpecialized = null
        durationSpecializedT = null
        encoderSpecializedT = null
        vectorSpecializedKey = null
        vocoderSpecializedL = null
    }

    /**
     * QNN HTP does not accept unresolved dynamic dimensions. ORT exposes the
     * symbolic dimension names through TensorInfo and provides the official
     * SessionOptions.setSymbolicDimensionValue(name, value) API to bind them
     * before provider partitioning. We open a CPU metadata-only session, map
     * the model's actual symbolic names to the concrete T/L values for this
     * synthesis, then build the QNN hybrid session with those overrides.
     */
    private fun discoverSymbolicOverrides(
        fileName: String,
        graphName: String,
        concreteInputShapes: Map<String, LongArray?>,
        concreteOutputShape: LongArray?,
    ): Map<String, Long> {
        val model = File(config.modelDir, fileName)
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            setLoggerId("supertonic_${graphName}_shape_probe")
            setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            setSessionLogVerbosityLevel(0)
        }
        val result = linkedMapOf<String, Long>()
        try {
            env.createSession(model.absolutePath, opts).use { probe ->
                fun bindTensor(
                    tensorName: String,
                    info: TensorInfo,
                    concrete: LongArray?,
                    source: String,
                ) {
                    Log.i(
                        TAG,
                        "[SHAPE-META] graph=$graphName tensor=$tensorName source=$source " +
                            "shape=${info.shape.contentToString()} dims=${info.dimensionNames.contentToString()} " +
                            "concrete=${concrete?.contentToString() ?: "unknown"}",
                    )
                    if (concrete == null || concrete.size != info.shape.size) return
                    info.shape.indices.forEach { i ->
                        if (info.shape[i] >= 0) return@forEach
                        val dimName = info.dimensionNames.getOrNull(i).orEmpty()
                        if (dimName.isBlank()) {
                            Log.w(TAG, "[FREE-DIM-UNNAMED] graph=$graphName tensor=$tensorName axis=$i")
                            return@forEach
                        }
                        val value = concrete[i]
                        val previous = result[dimName]
                        require(previous == null || previous == value) {
                            "Conflicting symbolic dimension '$dimName': $previous vs $value " +
                                "($graphName/$tensorName axis=$i)"
                        }
                        result[dimName] = value
                    }
                }

                concreteInputShapes.forEach { (semantic, concrete) ->
                    val actual = probe.inputNames.firstOrNull { it == semantic }
                        ?: probe.inputNames.firstOrNull { it.endsWith(semantic) || it.contains(semantic) }
                        ?: return@forEach
                    val info = probe.inputInfo[actual]?.info as? TensorInfo ?: return@forEach
                    bindTensor(actual, info, concrete, "input")
                }
                val output = probe.outputNames.firstOrNull()
                if (output != null) {
                    val info = probe.outputInfo[output]?.info as? TensorInfo
                    if (info != null) bindTensor(output, info, concreteOutputShape, "output")
                }
            }
        } finally {
            opts.close()
        }
        require(result.isNotEmpty()) {
            "No named symbolic dimensions found for $graphName; QNN static specialization cannot be applied"
        }
        return result
    }

    fun backendReport(): String = when (config.backend) {
        InferenceBackend.CPU_ORT,
        InferenceBackend.CPU_XNNPACK ->
            "$variantName / ORT CPU EP / dynamic / threads=${config.numThreads.coerceIn(1, 64)} / REV30-fast-path"
        InferenceBackend.CPU_XNNPACK_FP16 ->
            "$variantName / ORT CPU EP / dynamic / threads=${config.numThreads.coerceIn(1, 64)} / REV30-fast-path (forced-FP16 N/A to ORT)"
        InferenceBackend.ONNX_XNNPACK ->
            "$variantName / ORT XNNPACK EP + CPU fallback / dynamic / XNN=graph-adaptive<=${config.numThreads.coerceIn(1, 64)} ORT=${config.numThreads.coerceIn(1, 64)} per-session"
        InferenceBackend.QUALCOMM_NPU -> if (useSm6350Hta) {
            when (config.ttsModel) {
                TtsModel.SUPERTONIC_ONNX_W8A16_QDQ ->
                    "$variantName / ORT QNN HTA COMPAT + CPU FALLBACK / static W8A16 QDQ / adaptive T=${QNN_TEXT_BUCKETS.joinToString("/")} L=${QNN_LATENT_BUCKETS.joinToString("/")} / context off"
                else ->
                    "$variantName / ORT QNN HTA COMPAT + CPU FALLBACK / adaptive T=${QNN_TEXT_BUCKETS.joinToString("/")} L=${QNN_LATENT_BUCKETS.joinToString("/")} / context off"
            }
        } else {
            when (config.ttsModel) {
                TtsModel.SUPERTONIC_ONNX_W8A16_QDQ ->
                    "$variantName / ORT QNN HTP + CPU HYBRID / static W8A16 QDQ / adaptive T=${QNN_TEXT_BUCKETS.joinToString("/")} L=${QNN_LATENT_BUCKETS.joinToString("/")}"
                else ->
                    "$variantName / ORT QNN HTP + CPU HYBRID / adaptive T=${QNN_TEXT_BUCKETS.joinToString("/")} L=${QNN_LATENT_BUCKETS.joinToString("/")}"
            }
        }
    }

    fun lastProfile(): String = profile

    fun stop() {
        stopped.set(true)
        synchronized(pregenRunners) {
            pregenRunners.forEach { runCatching { it.stop() } }
        }
    }

    private data class StreamChunkResult(
        val pcm: ByteArray,
        val childProfile: String,
    )

    @Synchronized
    private fun ensurePregenRunners(depth: Int, workerThreads: Int): List<OnnxSupertonicRunner> {
        val target = depth.coerceIn(1, 3)
        val targetThreads = workerThreads.coerceIn(1, 64)
        synchronized(pregenRunners) {
            val mustRebuild = pregenRunners.size != target ||
                pregenRunners.any { it.config.numThreads != targetThreads }
            if (mustRebuild) {
                pregenRunners.forEach { runCatching { it.close() } }
                pregenRunners.clear()
                repeat(target) {
                    // Speculative runners intentionally use a fraction of the foreground
                    // CPU budget.  Otherwise every runner creates a full XNNPACK pool and
                    // N runners x N threads oversubscribe mobile CPUs.
                    pregenRunners += OnnxSupertonicRunner(config.copy(numThreads = targetThreads))
                }
            }
            return pregenRunners.toList()
        }
    }

    private fun trimPcm16TrailingSilence(pcm: ByteArray, maxTrimMs: Int): ByteArray {
        if (pcm.size < 4 || maxTrimMs <= 0) return pcm
        val maxSamples = ((sampleRate.toLong() * maxTrimMs.toLong()) / 1000L)
            .coerceAtMost(pcm.size.toLong() / 2L)
            .toInt()
        if (maxSamples <= 0) return pcm
        // Conservative -50 dBFS-ish gate. Only remove a contiguous silent tail and never
        // more than the user-selected cap; voiced consonant endings are left untouched.
        val threshold = 104
        var sampleIndex = pcm.size / 2 - 1
        var removable = 0
        while (sampleIndex >= 0 && removable < maxSamples) {
            val byteIndex = sampleIndex * 2
            val lo = pcm[byteIndex].toInt() and 0xff
            val hi = pcm[byteIndex + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            if (kotlin.math.abs(sample) > threshold) break
            removable++
            sampleIndex--
        }
        if (removable == 0) return pcm
        return pcm.copyOf(pcm.size - removable * 2)
    }

    private fun profileValue(source: String, key: String): Double? = source
        .split(';')
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.toDoubleOrNull()

    /**
     * Real ONNX streaming/pre-generation path.
     *
     * CPU ONNX uses one full-thread look-ahead runner. It is launched after playable
     * first-chunk PCM exists and overlaps its work with Android playback.
     * QNN/HTP currently uses serialized one-chunk look-ahead: after a chunk is emitted,
     * the same NPU runner immediately generates the next chunk while playback continues.
     * This avoids concurrent HTP sessions and keeps peak HTP/native memory bounded
     * while adaptive bucket contexts are swapped from the persistent cache. It is still real pre-generation relative to playback,
     * but requested queue depth is reported separately from effective NPU depth.
     */
    fun synthesizeStreaming(
        text: String,
        language: String,
        voiceId: String,
        totalSteps: Int,
        speed: Float,
        preGeneration: Boolean,
        preGenerationQueue: Int,
        chunkGapMinMs: Int,
        chunkGapMaxMs: Int,
        trailingSilenceTrimMs: Int,
        onChunk: (ByteArray, Boolean) -> Unit,
    ) {
        check(streamingActive.compareAndSet(false, true)) { "ONNX streaming synthesis is already active" }
        stopped.set(false)
        try {
        val startedNs = System.nanoTime()
        val chunks = chunkText(text, if (language == "ko" || language == "ja") 120 else 300)
        require(chunks.isNotEmpty()) { "Text is empty after preprocessing" }
        val effectivePregen = preGeneration && config.backend != InferenceBackend.QUALCOMM_NPU
        val requestedDepth = if (effectivePregen) 1 else 0
        val parallelDepth = if (
            effectivePregen &&
            (config.backend == InferenceBackend.CPU_ORT ||
                config.backend == InferenceBackend.CPU_XNNPACK ||
                config.backend == InferenceBackend.CPU_XNNPACK_FP16 ||
                config.backend == InferenceBackend.ONNX_XNNPACK) &&
            chunks.size > 1
        ) 1 else 0 // one full-speed look-ahead worker; NPU pregen is deliberately OFF.
        val effectiveDepth = parallelDepth
        val pregenWorkerThreads = if (parallelDepth > 0)
            config.numThreads.coerceIn(1, 64) else 0

        Log.i(
            TAG,
            "[ONNX-STREAM-BEGIN] backend=${config.backend.name} chunks=${chunks.size} " +
                "pregen=${if (effectivePregen) 1 else 0} requestedDepth=$requestedDepth " +
                "effectiveDepth=$effectiveDepth parallel=${if (parallelDepth > 0) 1 else 0} " +
                "foregroundThreads=${config.numThreads.coerceIn(1, 64)} workerThreads=$pregenWorkerThreads " +
                "workerBudget=${pregenWorkerThreads * parallelDepth} launchAfterFirstAudio=${if (parallelDepth > 0) 1 else 0}",
        )

        var totalAudioSec = 0.0
        var durationMs = 0.0
        var encoderMs = 0.0
        var vectorMs = 0.0
        var vocoderMs = 0.0
        var pregenUsed = 0
        var pregenSetupMs = 0.0
        var pregenLaunchMs = 0.0
        var pregenWaitMs = 0.0
        var pregenSetupFailed = 0
        var firstAudioMs = -1.0
        var lastEmitNs = 0L
        var gapTotalMs = 0.0
        var gapMaxSeenMs = 0.0
        var gapCount = 0
        var gapOverMax = 0

        fun accumulate(childProfile: String) {
            durationMs += profileValue(childProfile, "duration") ?: 0.0
            encoderMs += profileValue(childProfile, "encoder") ?: 0.0
            vectorMs += profileValue(childProfile, "vector") ?: 0.0
            vocoderMs += profileValue(childProfile, "vocoder") ?: 0.0
        }

        fun emit(
            result: StreamChunkResult,
            final: Boolean,
            wasPregen: Boolean,
            afterPrime: (() -> Unit)? = null,
        ) {
            ensureNotStopped()
            accumulate(result.childProfile)
            val prepared = trimPcm16TrailingSilence(result.pcm, trailingSilenceTrimMs.coerceIn(0, 500))

            fun push(bytes: ByteArray) {
                if (bytes.isEmpty()) return
                if (lastEmitNs != 0L) {
                    val now = System.nanoTime()
                    val elapsed = (now - lastEmitNs) / 1_000_000.0
                    val minGap = chunkGapMinMs.coerceIn(0, 2000)
                    if (elapsed < minGap) {
                        Thread.sleep((minGap - elapsed).toLong().coerceAtLeast(0L))
                    }
                    val actualGap = (System.nanoTime() - lastEmitNs) / 1_000_000.0
                    gapTotalMs += actualGap
                    gapMaxSeenMs = maxOf(gapMaxSeenMs, actualGap)
                    gapCount++
                    if (actualGap > chunkGapMaxMs.coerceIn(0, 2000)) gapOverMax++
                }
                if (firstAudioMs < 0.0) firstAudioMs = elapsedMs(startedNs)
                onChunk(bytes, false)
                totalAudioSec += bytes.size / 2.0 / sampleRate
                lastEmitNs = System.nanoTime()
            }

            if (prepared.isNotEmpty() && afterPrime != null) {
                // Give Android ~1.25 s of playable PCM, then start speculative setup/work
                // asynchronously while the rest of the first chunk is being consumed.
                val primeBytes = minOf(prepared.size, (sampleRate * 2 * 5 / 4)).and(-2)
                if (primeBytes > 0) push(prepared.copyOfRange(0, primeBytes))
                afterPrime.invoke()
                if (primeBytes < prepared.size) push(prepared.copyOfRange(primeBytes, prepared.size))
            } else {
                push(prepared)
                if (afterPrime != null) afterPrime.invoke()
            }
            if (prepared.isNotEmpty() && wasPregen) pregenUsed++
            if (final) onChunk(ByteArray(0), true)
        }

        fun produce(runner: OnnxSupertonicRunner, chunk: String): StreamChunkResult {
            ensureNotStopped()
            val pcm = runner.synthesize(chunk, language, voiceId, totalSteps, speed)
            return StreamChunkResult(pcm, runner.lastProfile())
        }

        if (parallelDepth > 0) {
            // Generate chunk 0 with the foreground runner only.  Once ~1.25 s of its
            // audio has been handed to Android, start one full-thread speculative runner
            // on a background executor.  This overlaps setup/VE with playback rather than
            // foreground inference or a fully-completed first-chunk callback.
            val first = produce(this, chunks[0])
            val executor = Executors.newSingleThreadExecutor()
            var worker: OnnxSupertonicRunner? = null
            data class Pending(val index: Int, val future: Future<StreamChunkResult>)
            var pending: Pending? = null
            var next = 1

            fun launchOne(index: Int) {
                val launchNs = System.nanoTime()
                val future = executor.submit(Callable {
                    var localWorker = worker
                    if (localWorker == null) {
                        val setupNs = System.nanoTime()
                        localWorker = ensurePregenRunners(1, pregenWorkerThreads).first()
                        val preparedWorker = requireNotNull(localWorker)
                        preparedWorker.streamingActive.set(true)
                        preparedWorker.stopped.set(false)
                        worker = preparedWorker
                        val setupMs = elapsedMs(setupNs)
                        pregenSetupMs += setupMs
                        Log.i(
                            TAG,
                            "[ONNX-PREGEN-SETUP] requested_depth=$requestedDepth effective_depth=1 " +
                                "worker_threads=$pregenWorkerThreads foreground_threads=${config.numThreads.coerceIn(1, 64)} " +
                                "setup_ms=${formatMs(setupMs)} during_first_audio=1",
                        )
                    }
                    produce(requireNotNull(localWorker), chunks[index])
                })
                pending = Pending(index, future)
                val launchMs = elapsedMs(launchNs)
                pregenLaunchMs += launchMs
                Log.i(
                    TAG,
                    "[ONNX-PREGEN-LAUNCH] chunk=$index worker=0 launch_ms=${formatMs(launchMs)} " +
                        "worker_threads=$pregenWorkerThreads",
                )
            }

            try {
                val warmPregenRunner = synchronized(pregenRunners) {
                    pregenRunners.size == 1 && pregenRunners[0].config.numThreads == pregenWorkerThreads
                }
                if (warmPregenRunner && next < chunks.size) {
                    // Warm path: launch before the blocking Android audio callback so
                    // speculative inference overlaps the entire first-chunk handoff.
                    launchOne(next++)
                }
                emit(
                    first,
                    final = false,
                    wasPregen = false,
                    afterPrime = if (warmPregenRunner) null else {
                        {
                            if (pending == null && next < chunks.size) launchOne(next++)
                        }
                    },
                )
                for (index in 1 until chunks.size) {
                    ensureNotStopped()
                    if (pending == null) launchOne(index)
                    val task = requireNotNull(pending)
                    require(task.index == index) { "ONNX pre-generation queue lost order: ${task.index} != $index" }
                    val waitNs = System.nanoTime()
                    val result = task.future.get()
                    val waitMs = elapsedMs(waitNs)
                    pregenWaitMs += waitMs
                    Log.i(TAG, "[ONNX-PREGEN-WAIT] chunk=$index worker=0 wait_ms=${formatMs(waitMs)}")
                    pending = null
                    if (next < chunks.size) launchOne(next++)
                    emit(result, final = index == chunks.lastIndex, wasPregen = true)
                }
            } catch (t: Throwable) {
                pregenSetupFailed++
                Log.w(TAG, "[ONNX-PREGEN-FALLBACK] speculative path failed; continuing sequentially", t)
                val startIndex = (pending?.index ?: next).coerceAtLeast(1)
                pending?.future?.cancel(true)
                pending = null
                for (index in startIndex until chunks.size) {
                    val result = produce(this, chunks[index])
                    emit(result, final = index == chunks.lastIndex, wasPregen = false)
                }
            } finally {
                pending?.future?.cancel(true)
                executor.shutdownNow()
                worker?.streamingActive?.set(false)
            }
        } else {
            chunks.forEachIndexed { index, chunk ->
                val result = produce(this, chunk)
                emit(
                    result,
                    final = index == chunks.lastIndex,
                    wasPregen = false,
                )
            }
        }

        val totalMs = elapsedMs(startedNs)
        profile = buildString {
            append("total=").append(formatMs(totalMs))
            append(";duration=").append(formatMs(durationMs))
            append(";encoder=").append(formatMs(encoderMs))
            append(";vector=").append(formatMs(vectorMs))
            append(";vocoder=").append(formatMs(vocoderMs))
            append(";onnx_variant=").append(config.ttsModel.name)
            append(";original_audio_sec=").append(String.format(Locale.US, "%.4f", totalAudioSec))
            append(";original_runtime=ORT_stream")
            append(";vector_tensor_reuse=1")
            append(";original_backend=").append(config.backend.name)
            append(";original_execution=").append(
                if (config.backend == InferenceBackend.QUALCOMM_NPU)
                    if (useSm6350Hta) "QNN_HTA_COMPAT_PLUS_CPU_FALLBACK_FIXED_SHAPE" else "QNN_HTP_PLUS_CPU_HYBRID_FIXED_SHAPE"
                else if (config.backend == InferenceBackend.CPU_ORT ||
                    config.backend == InferenceBackend.CPU_XNNPACK ||
                    config.backend == InferenceBackend.CPU_XNNPACK_FP16)
                    "ORT_CPU_EP_REV30_FAST_PATH"
                else if (config.backend == InferenceBackend.ONNX_XNNPACK)
                    "ORT_XNNPACK_EP_PLUS_CPU_FALLBACK"
                else "ORT_${config.backend.name}"
            )
            append(";pregen=").append(if (effectivePregen) "on" else "off")
            append(";pregen_queue_depth=").append(requestedDepth)
            append(";pregen_effective_depth=").append(effectiveDepth)
            append(";pregen_used_chunks=").append(pregenUsed)
            append(";pregen_worker_threads=").append(pregenWorkerThreads)
            append(";pregen_worker_budget=").append(pregenWorkerThreads * parallelDepth)
            append(";pregen_setup=").append(formatMs(pregenSetupMs))
            append(";pregen_launch=").append(formatMs(pregenLaunchMs))
            append(";pregen_wait=").append(formatMs(pregenWaitMs))
            append(";pregen_setup_failed=").append(pregenSetupFailed)
            append(";pregen_launch_during_first_audio=").append(if (parallelDepth > 0) 1 else 0)
            append(";ort_shared_pool=0")
            append(";ort_fallback_threads=").append(config.numThreads.coerceIn(1, 64))
            append(";threads_duration=").append(minOf(2, config.numThreads.coerceIn(1, 64)))
            append(";threads_encoder=").append(minOf(4, config.numThreads.coerceIn(1, 64)))
            append(";threads_vector=").append(config.numThreads.coerceIn(1, 64))
            append(";threads_vocoder=").append(config.numThreads.coerceIn(1, 64))
            append(";streamed_chunks=").append(chunks.size)
            append(";ttfa=").append(formatMs(firstAudioMs.coerceAtLeast(0.0)))
            append(";avg_chunk_gap_ms=").append(formatMs(if (gapCount > 0) gapTotalMs / gapCount else 0.0))
            append(";max_chunk_gap_ms=").append(formatMs(gapMaxSeenMs))
            append(";chunk_gap_over_max_count=").append(gapOverMax)
            append(";original_ids=int64")
            append(";deep_profiler=").append(if (config.enableDeepProfiler) 1 else 0)
        }
        Log.i(TAG, "[ONNX-STREAM-END] profile=$profile")
        } finally {
            finishDeepProfiling()
            streamingActive.set(false)
        }
    }

    fun synthesize(
        text: String,
        language: String,
        voiceId: String,
        totalSteps: Int,
        speed: Float,
    ): ByteArray {
        if (!streamingActive.get()) stopped.set(false)
        if (config.backend == InferenceBackend.QUALCOMM_NPU) {
            return synthesizeFixedNpu(text, language, voiceId, totalSteps, speed)
        }
        val startNs = System.nanoTime()
        val steps = totalSteps.coerceIn(1, 64)
        val safeSpeed = speed.coerceIn(0.25f, 3.0f)
        val style = voiceStyle(voiceId)
        val chunks = chunkText(text, if (language == "ko" || language == "ja") 120 else 300)
        require(chunks.isNotEmpty()) { "Text is empty after preprocessing" }

        val pcm = ByteArrayOutputStream()
        var totalAudioSec = 0.0
        var durationMs = 0.0
        var encoderMs = 0.0
        var vectorMs = 0.0
        var vocoderMs = 0.0

        Log.i(
            TAG,
            "[SYNTH-BEGIN] backend=${config.backend.name} lang=$language chunks=${chunks.size} " +
                "steps=$steps speed=$safeSpeed dynamic=1 ids=int64",
        )

        chunks.forEachIndexed { index, chunk ->
            ensureNotStopped()
            val prepared = tokenize(chunk, language)
            val t = prepared.length
            Log.i(TAG, "[CHUNK] index=$index textT=$t")

            val t0 = System.nanoTime()
            Log.i(TAG, "[STAGE-BEGIN] chunk=$index stage=duration")
            val durationSecRaw = try {
                runDuration(prepared, style)
            } catch (t: Throwable) {
                Log.e(TAG, "[STAGE-FAIL] chunk=$index stage=duration afterMs=${formatMs(elapsedMs(t0))} msg=${t.message}", t)
                throw t
            }
            val durationSec = durationSecRaw / safeSpeed
            durationMs += elapsedMs(t0)
            Log.i(TAG, "[STAGE-END] chunk=$index stage=duration ms=${formatMs(elapsedMs(t0))} value=$durationSec")
            require(durationSec.isFinite() && durationSec > 0f) {
                "$variantName duration invalid: $durationSec"
            }

            val t1 = System.nanoTime()
            Log.i(TAG, "[STAGE-BEGIN] chunk=$index stage=encoder")
            val textEmb = try {
                runEncoder(prepared, style)
            } catch (t: Throwable) {
                Log.e(TAG, "[STAGE-FAIL] chunk=$index stage=encoder afterMs=${formatMs(elapsedMs(t1))} msg=${t.message}", t)
                throw t
            }
            encoderMs += elapsedMs(t1)
            Log.i(TAG, "[STAGE-END] chunk=$index stage=encoder ms=${formatMs(elapsedMs(t1))}")

            val wavLength = (durationSec * modelConfig.sampleRate).toLong().coerceAtLeast(1L)
            val l = ceil(wavLength.toDouble() / modelConfig.chunkSamples.toDouble())
                .toInt()
                .coerceAtLeast(1)
            val latentMask = FloatArray(l) { i ->
                if (i < ceil(wavLength.toDouble() / modelConfig.chunkSamples).toInt()) 1f else 0f
            }
            var latent = FloatArray(modelConfig.latentChannels * l) { rng.nextGaussian().toFloat() }
            for (i in 0 until l) {
                val m = latentMask[i]
                if (m != 1f) {
                    for (c in 0 until modelConfig.latentChannels) {
                        latent[c * l + i] *= m
                    }
                }
            }

            val t2 = System.nanoTime()
            Log.i(TAG, "[STAGE-BEGIN] chunk=$index stage=vector steps=$steps latentL=$l")
            try {
                latent = runVectorSteps(
                    graph = vectorSession(t, l),
                    initialLatent = latent,
                    textEmb = textEmb,
                    style = style,
                    textMask = prepared.mask,
                    latentMask = latentMask,
                    textT = t,
                    latentL = l,
                    steps = steps,
                    chunkIndex = index,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "[STAGE-FAIL] chunk=$index stage=vector afterMs=${formatMs(elapsedMs(t2))} msg=${t.message}", t)
                throw t
            }
            vectorMs += elapsedMs(t2)
            Log.i(TAG, "[STAGE-END] chunk=$index stage=vector ms=${formatMs(elapsedMs(t2))}")

            val t3 = System.nanoTime()
            Log.i(TAG, "[STAGE-BEGIN] chunk=$index stage=vocoder latentL=$l")
            val wav = try {
                runVocoder(latent, l)
            } catch (t: Throwable) {
                Log.e(TAG, "[STAGE-FAIL] chunk=$index stage=vocoder afterMs=${formatMs(elapsedMs(t3))} msg=${t.message}", t)
                throw t
            }
            vocoderMs += elapsedMs(t3)
            Log.i(TAG, "[STAGE-END] chunk=$index stage=vocoder ms=${formatMs(elapsedMs(t3))}")
            validateFinite("vocoder", wav)

            val trimSamples = wavLength.coerceAtMost(wav.size.toLong()).toInt()
            pcm.write(floatToPcm16(wav, trimSamples))
            totalAudioSec += trimSamples.toDouble() / modelConfig.sampleRate

            if (index != chunks.lastIndex) {
                val silenceSamples = (SILENCE_SECONDS * modelConfig.sampleRate).roundToInt()
                pcm.write(ByteArray(silenceSamples * 2))
                totalAudioSec += SILENCE_SECONDS
            }
            Log.i(
                TAG,
                "[CHUNK-DONE] index=$index duration=$durationSec textT=$t latentL=$l " +
                    "wavSamples=$trimSamples",
            )
        }

        val totalMs = elapsedMs(startNs)
        profile = buildString {
            append("total=").append(formatMs(totalMs))
            append(";duration=").append(formatMs(durationMs))
            append(";encoder=").append(formatMs(encoderMs))
            append(";vector=").append(formatMs(vectorMs))
            append(";vocoder=").append(formatMs(vocoderMs))
            append(";onnx_variant=").append(config.ttsModel.name)
            append(";original_audio_sec=").append(String.format(Locale.US, "%.4f", totalAudioSec))
            append(";original_runtime=ORT_dynamic")
            append(";vector_tensor_reuse=1")
            append(";original_backend=").append(config.backend.name)
            if (config.backend == InferenceBackend.QUALCOMM_NPU) {
                append(";original_execution=").append(
                    if (useSm6350Hta) "QNN_HTA_COMPAT_PLUS_CPU_FALLBACK_FIXED_SHAPE" else "QNN_HTP_PLUS_CPU_HYBRID_FIXED_SHAPE"
                )
            } else if (config.backend == InferenceBackend.CPU_ORT ||
                config.backend == InferenceBackend.CPU_XNNPACK ||
                config.backend == InferenceBackend.CPU_XNNPACK_FP16) {
                append(";original_execution=ORT_CPU_EP_REV30_FAST_PATH")
            } else if (config.backend == InferenceBackend.ONNX_XNNPACK) {
                append(";original_execution=ORT_XNNPACK_EP_PLUS_CPU_FALLBACK")
            } else {
                append(";original_execution=ORT_").append(config.backend.name)
            }
            append(";original_ids=int64")
            append(";deep_profiler=").append(if (config.enableDeepProfiler) 1 else 0)
        }
        Log.i(TAG, "[SYNTH-END] bytes=${pcm.size()} profile=$profile")
        finishDeepProfiling()
        return pcm.toByteArray()
    }

    private data class FixedPlan(
        val rawText: String,
        val input: TextInput,
        val actualT: Int,
        val bucketT: Int,
        val durationSec: Float,
        val actualL: Int,
        val bucketL: Int,
        val wavLength: Long,
    )

    private fun synthesizeFixedNpu(
        text: String,
        language: String,
        voiceId: String,
        totalSteps: Int,
        speed: Float,
    ): ByteArray {
        val startNs = System.nanoTime()
        if (config.ttsModel == TtsModel.SUPERTONIC_ONNX_W8A16_QDQ) {
            w8a16DurationRecoveries = 0
            w8a16DurationLastNpu = Float.NaN
            w8a16DurationLastCpuActual = Float.NaN
            w8a16DurationLastCpuPadded = Float.NaN
        }
        val steps = totalSteps.coerceIn(1, 64)
        val safeSpeed = speed.coerceIn(0.25f, 3.0f)
        val style = voiceStyle(voiceId)
        val seedChunks = chunkText(text, if (language == "ko" || language == "ja") 120 else 300)
        require(seedChunks.isNotEmpty()) { "Text is empty after preprocessing" }

        var durationMs = 0.0
        val plans = ArrayList<FixedPlan>()
        fun plan(raw: String, depth: Int) {
            require(depth <= 20) { "Original adaptive-shape recursive split exceeded safety limit" }
            ensureNotStopped()
            val actual = tokenize(raw, language)
            if (actual.length > fixedTextT) {
                val halves = splitForFixedShape(raw)
                require(halves != null) {
                    "Adaptive QNN max T=$fixedTextT is too small for textT=${actual.length}; cannot split further"
                }
                Log.i(TAG, "[BUCKET-SPLIT] reason=T actual=${actual.length} max=$fixedTextT depth=$depth")
                plan(halves.first, depth + 1)
                plan(halves.second, depth + 1)
                return
            }

            val bucketT = selectBucket(actual.length, QNN_TEXT_BUCKETS, "T")
            val padded = padTextInput(actual, bucketT)
            val t0 = System.nanoTime()
            val durationRaw = if (config.ttsModel == TtsModel.SUPERTONIC_ONNX_W8A16_QDQ) {
                // REV23/24: W8A16 QNN duration is numerically wrong. Keep one
                // dynamic CPU duration inference while the other stages use HTP.
                val cpuDuration = runW8A16CpuDuration(actual, style, "duration_cpu_dynamic")
                w8a16DurationLastNpu = Float.NaN
                w8a16DurationLastCpuPadded = Float.NaN
                w8a16DurationLastCpuActual = cpuDuration
                Log.i(
                    TAG,
                    "[W8A16-DURATION-CPU] mode=dynamic value=" +
                        String.format(Locale.US, "%.6f", cpuDuration),
                )
                cpuDuration
            } else {
                val graph = durationSession(bucketT)
                runDurationOnSession(
                    session = graph.session,
                    inputName = graph::input,
                    outputName = graph.outputName,
                    input = padded,
                    style = style,
                    stage = "duration_t$bucketT",
                )
            }
            durationMs += elapsedMs(t0)
            val durationSec = durationRaw / safeSpeed
            require(durationSec.isFinite() && durationSec > 0f) {
                "$variantName duration invalid: $durationSec"
            }
            val wavLength = (durationSec * modelConfig.sampleRate).toLong().coerceAtLeast(1L)
            val actualL = ceil(wavLength.toDouble() / modelConfig.chunkSamples.toDouble())
                .toInt().coerceAtLeast(1)
            if (actualL > fixedLatentL) {
                val halves = splitForFixedShape(raw)
                require(halves != null) {
                    "Adaptive QNN max L=$fixedLatentL is too small for latentL=$actualL; cannot split further"
                }
                Log.i(TAG, "[BUCKET-SPLIT] reason=L actual=$actualL max=$fixedLatentL depth=$depth")
                plan(halves.first, depth + 1)
                plan(halves.second, depth + 1)
                return
            }
            val bucketL = selectBucket(actualL, QNN_LATENT_BUCKETS, "L")
            Log.i(
                TAG,
                "[BUCKET-SELECT] actualT=${actual.length} actualL=$actualL -> T=$bucketT L=$bucketL",
            )
            plans += FixedPlan(
                rawText = raw,
                input = padded,
                actualT = actual.length,
                bucketT = bucketT,
                durationSec = durationSec,
                actualL = actualL,
                bucketL = bucketL,
                wavLength = wavLength,
            )
        }
        seedChunks.forEach { plan(it, 0) }

        val pcm = ByteArrayOutputStream()
        var totalAudioSec = 0.0
        var encoderMs = 0.0
        var vectorMs = 0.0
        var vocoderMs = 0.0
        var maxActualT = 0
        var maxActualL = 0
        var sumActualT = 0L
        var sumActualL = 0L
        var sumBucketT = 0L
        var sumBucketL = 0L
        val usedBuckets = linkedSetOf<String>()

        Log.i(
            TAG,
            "[SYNTH-BEGIN] backend=${config.backend.name} lang=$language chunks=${plans.size} " +
                "steps=$steps speed=$safeSpeed adaptiveBuckets=1 " +
                "T=${QNN_TEXT_BUCKETS.joinToString("/")} L=${QNN_LATENT_BUCKETS.joinToString("/")} ids=int64",
        )

        plans.forEachIndexed { index, plan ->
            ensureNotStopped()
            maxActualT = maxOf(maxActualT, plan.actualT)
            maxActualL = maxOf(maxActualL, plan.actualL)
            sumActualT += plan.actualT
            sumActualL += plan.actualL
            sumBucketT += plan.bucketT
            sumBucketL += plan.bucketL
            usedBuckets += "t${plan.bucketT}l${plan.bucketL}"
            Log.i(
                TAG,
                "[BUCKET-CHUNK] index=$index actualT=${plan.actualT}/${plan.bucketT} " +
                    "actualL=${plan.actualL}/${plan.bucketL} duration=${plan.durationSec}",
            )

            val t1 = System.nanoTime()
            val encoderGraph = encoderSession(plan.bucketT)
            val textEmb = try {
                runEncoderOnSession(
                    session = encoderGraph.session,
                    inputName = encoderGraph::input,
                    outputName = encoderGraph.outputName,
                    input = plan.input,
                    style = style,
                    stage = "encoder_t${plan.bucketT}",
                ).also {
                    if (fullTensorDiagnostics) logTensorStats("encoder", it)
                    validateFinite("encoder", it)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "[STAGE-FAIL] chunk=$index stage=encoder msg=${t.message}", t)
                throw t
            }
            encoderMs += elapsedMs(t1)

            val latentMask = FloatArray(plan.bucketL) { i -> if (i < plan.actualL) 1f else 0f }
            val latent = FloatArray(modelConfig.latentChannels * plan.bucketL)
            for (c in 0 until modelConfig.latentChannels) {
                val base = c * plan.bucketL
                for (i in 0 until plan.actualL) latent[base + i] = rng.nextGaussian().toFloat()
            }

            val t2 = System.nanoTime()
            val finalLatent = runVectorSteps(
                graph = vectorSession(plan.bucketT, plan.bucketL),
                initialLatent = latent,
                textEmb = textEmb,
                style = style,
                textMask = plan.input.mask,
                latentMask = latentMask,
                textT = plan.bucketT,
                latentL = plan.bucketL,
                steps = steps,
                chunkIndex = index,
            )
            vectorMs += elapsedMs(t2)

            val t3 = System.nanoTime()
            val wav = runVocoder(finalLatent, plan.bucketL)
            vocoderMs += elapsedMs(t3)
            validateFinite("vocoder", wav)

            val trimSamples = plan.wavLength.coerceAtMost(wav.size.toLong()).toInt()
            pcm.write(floatToPcm16(wav, trimSamples))
            totalAudioSec += trimSamples.toDouble() / modelConfig.sampleRate
            if (index != plans.lastIndex) {
                val silenceSamples = (SILENCE_SECONDS * modelConfig.sampleRate).roundToInt()
                pcm.write(ByteArray(silenceSamples * 2))
                totalAudioSec += SILENCE_SECONDS
            }
        }

        val n = plans.size.coerceAtLeast(1)
        val totalMs = elapsedMs(startNs)
        val avgT = sumActualT.toDouble() / n
        val avgL = sumActualL.toDouble() / n
        val tPadding = if (sumBucketT > 0L) 1.0 - (sumActualT.toDouble() / sumBucketT.toDouble()) else 0.0
        val lPadding = if (sumBucketL > 0L) 1.0 - (sumActualL.toDouble() / sumBucketL.toDouble()) else 0.0
        profile = buildString {
            append("total=").append(formatMs(totalMs))
            append(";duration=").append(formatMs(durationMs))
            append(";encoder=").append(formatMs(encoderMs))
            append(";vector=").append(formatMs(vectorMs))
            append(";vocoder=").append(formatMs(vocoderMs))
            append(";onnx_variant=").append(config.ttsModel.name)
            append(";original_audio_sec=").append(String.format(Locale.US, "%.4f", totalAudioSec))
            append(";original_runtime=ORT_adaptive_bucket")
            append(";original_backend=QUALCOMM_NPU")
            append(";original_execution=").append(
                if (useSm6350Hta) "QNN_HTA_COMPAT_PLUS_CPU_FALLBACK_ADAPTIVE_BUCKET" else "QNN_HTP_PLUS_CPU_HYBRID_ADAPTIVE_BUCKET"
            )
            // Keep the legacy max-shape keys for UI/parser compatibility.
            append(";original_fixed_t=").append(fixedTextT)
            append(";original_fixed_l=").append(fixedLatentL)
            append(";original_bucket_t_grid=").append(QNN_TEXT_BUCKETS.joinToString("/"))
            append(";original_bucket_l_grid=").append(QNN_LATENT_BUCKETS.joinToString("/"))
            append(";original_used_buckets=").append(usedBuckets.joinToString(","))
            append(";original_qnn_context_duration=").append(
                if (config.ttsModel == TtsModel.SUPERTONIC_ONNX_W8A16_QDQ) "cpu_dynamic"
                else durationSpecialized?.contextState ?: "not_created"
            )
            append(";original_qnn_context_encoder=").append(encoderSpecialized?.contextState ?: "not_created")
            append(";original_qnn_context_vector=").append(vectorSpecialized?.contextState ?: "not_created")
            append(";original_qnn_context_vocoder=").append(vocoderSpecialized?.contextState ?: "not_created")
            append(";vector_tensor_reuse=1")
            append(";qnn_backend=").append(if (useSm6350Hta) "HTA" else "HTP")
            append(";qnn_cpu_fallback=").append("1")
            append(";qnn_shared_memory_allocator=").append(if (useSm6350Hta) "0" else "1")
            append(";qnn_graph_io_qdq=").append(if (useSm6350Hta) "default" else "QNN")
            append(";qnn_finalization_mode=").append(if (useSm6350Hta) "0" else "3")
            if (config.ttsModel == TtsModel.SUPERTONIC_ONNX_W8A16_QDQ) {
                append(";w8a16_duration_recoveries=").append(w8a16DurationRecoveries)
                append(";w8a16_duration_npu=").append(String.format(Locale.US, "%.6f", w8a16DurationLastNpu))
                append(";w8a16_duration_cpu_padded=").append(String.format(Locale.US, "%.6f", w8a16DurationLastCpuPadded))
                append(";w8a16_duration_cpu_actual=").append(String.format(Locale.US, "%.6f", w8a16DurationLastCpuActual))
            }
            append(";original_chunks=").append(plans.size)
            append(";original_max_actual_t=").append(maxActualT)
            append(";original_max_actual_l=").append(maxActualL)
            append(";original_avg_actual_t=").append(String.format(Locale.US, "%.2f", avgT))
            append(";original_avg_actual_l=").append(String.format(Locale.US, "%.2f", avgL))
            append(";original_t_padding_pct=").append(String.format(Locale.US, "%.2f", tPadding * 100.0))
            append(";original_l_padding_pct=").append(String.format(Locale.US, "%.2f", lPadding * 100.0))
            append(";original_ids=int64")
            append(";deep_profiler=").append(if (config.enableDeepProfiler) 1 else 0)
        }
        Log.i(TAG, "[SYNTH-END] bytes=${pcm.size()} profile=$profile")
        finishDeepProfiling()
        return pcm.toByteArray()
    }

    private fun padTextInput(input: TextInput, targetT: Int): TextInput {
        require(input.length <= targetT) { "textT=${input.length} exceeds fixed T=$targetT" }
        if (input.length == targetT) return input
        val ids = LongArray(targetT)
        val mask = FloatArray(targetT)
        input.ids.copyInto(ids, 0, 0, input.ids.size)
        input.mask.copyInto(mask, 0, 0, input.mask.size)
        return TextInput(ids, mask)
    }

    private fun splitForFixedShape(text: String): Pair<String, String>? {
        val clean = text.trim()
        val cpCount = clean.codePointCount(0, clean.length)
        if (cpCount < 2) return null
        val midCp = cpCount / 2
        val mid = clean.offsetByCodePoints(0, midCp)
        val candidates = ArrayList<Int>()
        fun collect(chars: String) {
            chars.forEach { ch ->
                var i = clean.indexOf(ch)
                while (i >= 0) {
                    if (i in 1 until clean.lastIndex) candidates += i + 1
                    i = clean.indexOf(ch, i + 1)
                }
            }
        }
        collect(".!?。！？;:，, ")
        val cut = candidates.minByOrNull { kotlin.math.abs(it - mid) } ?: mid
        val left = clean.substring(0, cut).trim()
        val right = clean.substring(cut).trim()
        if (left.isEmpty() || right.isEmpty()) {
            val rawCut = mid.coerceIn(1, clean.lastIndex)
            val l = clean.substring(0, rawCut).trim()
            val r = clean.substring(rawCut).trim()
            if (l.isEmpty() || r.isEmpty()) return null
            return l to r
        }
        return left to right
    }

    private fun expectedElementCount(shape: LongArray): Int {
        val count = shape.fold(1L) { acc, dim ->
            require(dim > 0L) { "Invalid output shape: ${shape.contentToString()}" }
            Math.multiplyExact(acc, dim)
        }
        require(count <= Int.MAX_VALUE) { "Output tensor too large: $count elements" }
        return count.toInt()
    }

    /** ONNX output path using caller-owned direct buffers. */
    private fun runFloatOutput(
        session: OrtSession,
        tensors: Map<String, OnnxTensor>,
        outputName: String,
        shape: LongArray,
        stage: String,
    ): FloatArray {
        val expected = expectedElementCount(shape)
        val out = directFloatBuffer(expected)
        val outTensor = OnnxTensor.createTensor(env, out, shape)
        outTensor.use {
            session.run(tensors, mapOf(outputName to it)).use { }
        }
        return out.toFloatArray()
    }

    private fun runDurationOnSession(
        session: OrtSession,
        inputName: (String) -> String,
        outputName: String,
        input: TextInput,
        style: VoiceStyle,
        stage: String,
    ): Float {
        val tensors = linkedMapOf<String, OnnxTensor>()
        try {
            tensors[inputName("text_ids")] = OnnxTensor.createTensor(
                env,
                directLongBuffer(input.ids),
                longArrayOf(1, input.length.toLong()),
            )
            tensors[inputName("style_dp")] = OnnxTensor.createTensor(
                env,
                directFloatBuffer(style.dp),
                style.dpShape,
            )
            tensors[inputName("text_mask")] = OnnxTensor.createTensor(
                env,
                directFloatBuffer(input.mask),
                longArrayOf(1, 1, input.length.toLong()),
            )
            val value = runFloatOutput(
                session = session,
                tensors = tensors,
                outputName = outputName,
                shape = longArrayOf(1),
                stage = stage,
            )[0]
            require(value.isFinite()) { "$variantName $stage produced NaN/Inf" }
            Log.i(TAG, "[TENSOR-STATS] stage=$stage n=1 value=${String.format(Locale.US, "%.6g", value)}")
            return value
        } finally {
            tensors.values.forEach { runCatching { it.close() } }
        }
    }

    private fun runDuration(input: TextInput, style: VoiceStyle): Float {
        val graph = durationDynamicLazy.value
        return runDurationOnSession(
            session = graph.session,
            inputName = graph::input,
            outputName = graph.outputName,
            input = input,
            style = style,
            stage = "duration",
        )
    }

    private fun runW8A16CpuDuration(input: TextInput, style: VoiceStyle, stage: String): Float {
        val session = w8a16DurationCpuSessionLazy.value
        fun inputName(semantic: String): String =
            if (semantic in session.inputNames) semantic
            else session.inputNames.firstOrNull { it.endsWith(semantic) || it.contains(semantic) }
                ?: error("W8A16 CPU duration input '$semantic' missing: ${session.inputNames}")
        val output = session.outputNames.firstOrNull() ?: error("W8A16 CPU duration output missing")
        return runDurationOnSession(session, ::inputName, output, input, style, stage)
    }

    private fun runEncoder(input: TextInput, style: VoiceStyle): FloatArray {
        val graph = encoderDynamicLazy.value
        val data = runEncoderOnSession(
            session = graph.session,
            inputName = graph::input,
            outputName = graph.outputName,
            input = input,
            style = style,
            stage = "encoder",
        )
        if (fullTensorDiagnostics) logTensorStats("encoder", data)
        validateFinite("encoder", data)
        return data
    }

    private fun runEncoderOnSession(
        session: OrtSession,
        inputName: (String) -> String,
        outputName: String,
        input: TextInput,
        style: VoiceStyle,
        stage: String,
    ): FloatArray {
        val tensors = linkedMapOf<String, OnnxTensor>()
        try {
            tensors[inputName("text_ids")] = OnnxTensor.createTensor(
                env,
                directLongBuffer(input.ids),
                longArrayOf(1, input.length.toLong()),
            )
            tensors[inputName("style_ttl")] = OnnxTensor.createTensor(
                env,
                directFloatBuffer(style.ttl),
                style.ttlShape,
            )
            tensors[inputName("text_mask")] = OnnxTensor.createTensor(
                env,
                directFloatBuffer(input.mask),
                longArrayOf(1, 1, input.length.toLong()),
            )
            return runFloatOutput(
                session = session,
                tensors = tensors,
                outputName = outputName,
                shape = longArrayOf(1, TEXT_EMBED_CHANNELS.toLong(), input.length.toLong()),
                stage = stage,
            )
        } finally {
            tensors.values.forEach { runCatching { it.close() } }
        }
    }

    /**
     * REV24 VE workspace: all seven input tensor wrappers and two latent/output
     * tensors are created once per utterance/chunk, then reused across every flow
     * step. Latent A/B ping-pong avoids a host FloatArray round-trip between steps.
     * This is provider-agnostic and benefits CPU as well as QNN HTP.
     */
    private inner class VectorWorkspace(
        private val graph: GraphSession,
        textEmb: FloatArray,
        style: VoiceStyle,
        textMask: FloatArray,
        latentMask: FloatArray,
        private val textT: Int,
        private val latentL: Int,
        totalSteps: Int,
    ) : AutoCloseable {
        private val latentCount = modelConfig.latentChannels * latentL
        private val latentShape = longArrayOf(1, modelConfig.latentChannels.toLong(), latentL.toLong())
        private val latentA = directFloatBuffer(latentCount)
        private val latentB = directFloatBuffer(latentCount)
        private val textEmbBuffer = directFloatBuffer(textEmb)
        private val styleBuffer = directFloatBuffer(style.ttl)
        private val latentMaskBuffer = directFloatBuffer(latentMask)
        private val textMaskBuffer = directFloatBuffer(textMask)
        private val currentStepBuffer = directFloatBuffer(1).apply { put(0, 0f) }
        private val totalStepBuffer = directFloatBuffer(1).apply { put(0, totalSteps.toFloat()) }

        private val latentATensor = OnnxTensor.createTensor(env, latentA, latentShape)
        private val latentBTensor = OnnxTensor.createTensor(env, latentB, latentShape)
        private val textEmbTensor = OnnxTensor.createTensor(
            env, textEmbBuffer, longArrayOf(1, TEXT_EMBED_CHANNELS.toLong(), textT.toLong()),
        )
        private val styleTensor = OnnxTensor.createTensor(env, styleBuffer, style.ttlShape)
        private val latentMaskTensor = OnnxTensor.createTensor(
            env, latentMaskBuffer, longArrayOf(1, 1, latentL.toLong()),
        )
        private val textMaskTensor = OnnxTensor.createTensor(
            env, textMaskBuffer, longArrayOf(1, 1, textT.toLong()),
        )
        private val currentStepTensor = OnnxTensor.createTensor(env, currentStepBuffer, longArrayOf(1))
        private val totalStepTensor = OnnxTensor.createTensor(env, totalStepBuffer, longArrayOf(1))

        private val noisyName = graph.input("noisy_latent")
        private val textEmbName = graph.input("text_emb")
        private val styleName = graph.input("style_ttl")
        private val latentMaskName = graph.input("latent_mask")
        private val textMaskName = graph.input("text_mask")
        private val currentStepName = graph.input("current_step")
        private val totalStepName = graph.input("total_step")
        private val outputName = graph.outputName

        private val inputsA = linkedMapOf(
            noisyName to latentATensor,
            textEmbName to textEmbTensor,
            styleName to styleTensor,
            latentMaskName to latentMaskTensor,
            textMaskName to textMaskTensor,
            currentStepName to currentStepTensor,
            totalStepName to totalStepTensor,
        )
        private val inputsB = linkedMapOf(
            noisyName to latentBTensor,
            textEmbName to textEmbTensor,
            styleName to styleTensor,
            latentMaskName to latentMaskTensor,
            textMaskName to textMaskTensor,
            currentStepName to currentStepTensor,
            totalStepName to totalStepTensor,
        )
        private val pinnedA = mapOf(outputName to latentATensor)
        private val pinnedB = mapOf(outputName to latentBTensor)

        fun run(initialLatent: FloatArray, steps: Int, chunkIndex: Int): FloatArray {
            require(initialLatent.size == latentCount) {
                "VE latent size mismatch: ${initialLatent.size} != $latentCount"
            }
            writeFloatBuffer(latentA, initialLatent)
            Log.i(
                TAG,
                "[VE-WORKSPACE] chunk=$chunkIndex tensorReuse=1 pingPong=1 textT=$textT latentL=$latentL steps=$steps",
            )
            repeat(steps) { step ->
                ensureNotStopped()
                currentStepBuffer.put(0, step.toFloat())
                val stepStartNs = System.nanoTime()
                val inputs = if (step % 2 == 0) inputsA else inputsB
                val pinned = if (step % 2 == 0) pinnedB else pinnedA
                graph.session.run(inputs, pinned).use { }
                val outputBuffer = if (step % 2 == 0) latentB else latentA

                // Keep two safety scans instead of scanning the whole latent twice
                // on every step. Full min/max/rms stats are opt-in via VERBOSE log.
                if (step == 0 || step == steps - 1) {
                    validateFiniteBuffer("vector_estimator step=$step", outputBuffer)
                    if (fullTensorDiagnostics) {
                        logTensorStatsBuffer("vector_step_${step}_of_$steps", outputBuffer)
                    }
                }
                Log.i(
                    TAG,
                    "[VE-STEP-END] chunk=$chunkIndex step=$step ms=${formatMs(elapsedMs(stepStartNs))}",
                )
            }
            val finalBuffer = if (steps % 2 == 0) latentA else latentB
            return finalBuffer.toFloatArray()
        }

        override fun close() {
            listOf(
                totalStepTensor,
                currentStepTensor,
                textMaskTensor,
                latentMaskTensor,
                styleTensor,
                textEmbTensor,
                latentBTensor,
                latentATensor,
            ).forEach { runCatching { it.close() } }
        }
    }

    private fun runVectorSteps(
        graph: GraphSession,
        initialLatent: FloatArray,
        textEmb: FloatArray,
        style: VoiceStyle,
        textMask: FloatArray,
        latentMask: FloatArray,
        textT: Int,
        latentL: Int,
        steps: Int,
        chunkIndex: Int,
    ): FloatArray = VectorWorkspace(
        graph = graph,
        textEmb = textEmb,
        style = style,
        textMask = textMask,
        latentMask = latentMask,
        textT = textT,
        latentL = latentL,
        totalSteps = steps,
    ).use { workspace ->
        workspace.run(initialLatent, steps, chunkIndex)
    }

    private fun runVocoder(latent: FloatArray, latentL: Int): FloatArray {
        val graph = vocoderSession(latentL)
        val tensors = linkedMapOf<String, OnnxTensor>()
        try {
            tensors[graph.input("latent")] = OnnxTensor.createTensor(
                env,
                directFloatBuffer(latent),
                longArrayOf(1, modelConfig.latentChannels.toLong(), latentL.toLong()),
            )
            val data = runFloatOutput(
                session = graph.session,
                tensors = tensors,
                outputName = graph.outputName,
                shape = longArrayOf(1, (modelConfig.chunkSamples * latentL).toLong()),
                stage = "vocoder",
            )
            if (fullTensorDiagnostics) logTensorStats("vocoder", data)
            return data
        } finally {
            tensors.values.forEach { runCatching { it.close() } }
        }
    }

    private fun readModelConfig(): ModelConfig {
        val file = File(config.modelDir, "tts.json")
        require(file.isFile) { "Supertone Original tts.json missing: ${file.absolutePath}" }
        val root = JSONObject(file.readText())
        val ae = root.optJSONObject("ae")
        val ttl = root.optJSONObject("ttl")
        val cfg = ModelConfig(
            sampleRate = ae?.optInt("sample_rate", DEFAULT_SAMPLE_RATE) ?: DEFAULT_SAMPLE_RATE,
            baseChunkSize = ae?.optInt("base_chunk_size", DEFAULT_BASE_CHUNK_SIZE)
                ?: DEFAULT_BASE_CHUNK_SIZE,
            chunkCompressFactor = ttl?.optInt("chunk_compress_factor", DEFAULT_CHUNK_COMPRESS_FACTOR)
                ?: DEFAULT_CHUNK_COMPRESS_FACTOR,
            latentDim = ttl?.optInt("latent_dim", DEFAULT_LATENT_DIM) ?: DEFAULT_LATENT_DIM,
        )
        require(cfg.sampleRate > 0 && cfg.baseChunkSize > 0 && cfg.chunkCompressFactor > 0 && cfg.latentDim > 0) {
            "Invalid Supertone Original tts.json dimensions: $cfg"
        }
        Log.i(
            TAG,
            "[MODEL-CONFIG] sampleRate=${cfg.sampleRate} baseChunk=${cfg.baseChunkSize} " +
                "compress=${cfg.chunkCompressFactor} latentDim=${cfg.latentDim} " +
                "latentChannels=${cfg.latentChannels} chunkSamples=${cfg.chunkSamples}",
        )
        return cfg
    }

    @Synchronized
    private fun voiceStyle(voiceId: String): VoiceStyle {
        if (styleCacheId == voiceId) styleCache?.let { return it }
        val file = File(config.modelDir, "voice_styles/$voiceId.json")
        require(file.isFile) { "Supertone Original voice style missing: ${file.absolutePath}" }
        val root = JSONObject(file.readText())
        fun parseStyle(name: String): Pair<FloatArray, LongArray> {
            val obj = root.getJSONObject(name)
            val dimsJson = obj.getJSONArray("dims")
            val dims = LongArray(dimsJson.length()) { i -> dimsJson.getLong(i) }
            val values = ArrayList<Float>()
            flattenNumbers(obj.get("data"), values)
            val expected = dims.fold(1L) { a, b -> a * b }
            require(expected == values.size.toLong()) {
                "$name size mismatch: expected=$expected actual=${values.size} dims=${dims.joinToString("x")}"
            }
            return values.toFloatArray() to dims
        }
        val (ttl, ttlShape) = parseStyle("style_ttl")
        val (dp, dpShape) = parseStyle("style_dp")
        return VoiceStyle(ttl, ttlShape, dp, dpShape).also {
            styleCacheId = voiceId
            styleCache = it
            Log.i(TAG, "[VOICE] id=$voiceId ttl=${ttlShape.joinToString("x")} dp=${dpShape.joinToString("x")}")
        }
    }

    private fun flattenNumbers(value: Any?, out: MutableList<Float>) {
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) flattenNumbers(value.get(i), out)
            is Number -> out.add(value.toFloat())
            else -> error("Unexpected voice style value: ${value?.javaClass?.name}")
        }
    }

    private fun tokenize(text: String, language: String): TextInput {
        require(language in AVAILABLE_LANGS) { "Invalid language: $language" }
        val processed = preprocessText(text, language)
        val cps = processed.codePoints().toArray()
        val ids = LongArray(cps.size) { i ->
            // Python reference casts ord(char) to np.uint16 before indexing.
            val unicodeValue = cps[i] and 0xffff
            require(unicodeValue < unicodeIndexer.size) {
                "unicode_indexer has no entry for U+${cps[i].toString(16).uppercase(Locale.US)} " +
                    "(uint16=$unicodeValue size=${unicodeIndexer.size})"
            }
            unicodeIndexer[unicodeValue]
        }
        val mask = FloatArray(ids.size) { 1f }
        return TextInput(ids, mask)
    }

    private fun preprocessText(input: String, language: String): String {
        var text = Normalizer.normalize(input, Normalizer.Form.NFKD)
        text = stripEmojiAndSymbols(text)
        val replacements = linkedMapOf(
            '–' to '-', '‑' to '-', '—' to '-', '_' to ' ',
            '“' to '"', '”' to '"', '‘' to '\'', '’' to '\'',
            '´' to '\'', '`' to '\'', '[' to ' ', ']' to ' ',
            '|' to ' ', '/' to ' ', '#' to ' ', '→' to ' ', '←' to ' ',
        )
        replacements.forEach { (from, to) -> text = text.replace(from, to) }
        text = text.replace(Regex("[♥☆♡©\\\\]"), "")
        text = text.replace("@", " at ")
            .replace("e.g.,", "for example, ")
            .replace("i.e.,", "that is, ")
        listOf(",", ".", "!", "?", ";", ":").forEach { p ->
            text = text.replace(" $p", p)
        }
        text = text.replace(" '", "'")
        while (text.contains("\"\"")) text = text.replace("\"\"", "\"")
        while (text.contains("''")) text = text.replace("''", "'")
        while (text.contains("``")) text = text.replace("``", "`")
        text = text.replace(Regex("\\s+"), " ").trim()
        val terminal = setOf('.', '!', '?', ';', ':', ',', '\'', '"', ')', ']', '}', '…', '。', '』', '】', '〉', '》', '›', '»')
        if (text.isNotEmpty() && text.last() !in terminal) text += "."
        return "<$language>$text</$language>"
    }

    private fun stripEmojiAndSymbols(text: String): String {
        val out = StringBuilder(text.length)
        text.codePoints().forEach { cp ->
            val remove =
                cp in 0x1F1E6..0x1F1FF || cp in 0x1F300..0x1FAFF ||
                    cp in 0x2600..0x26FF || cp in 0x2700..0x27BF
            if (!remove) out.appendCodePoint(cp)
        }
        return out.toString()
    }

    private fun chunkText(text: String, maxLen: Int): List<String> {
        val paragraphs = text.trim().split(Regex("\\n\\s*\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return emptyList()
        val chunks = ArrayList<String>()
        for (paragraph in paragraphs) {
            val sentences = paragraph.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            var current = ""
            for (sentence in sentences) {
                if (current.length + sentence.length + (if (current.isEmpty()) 0 else 1) <= maxLen) {
                    current += (if (current.isEmpty()) "" else " ") + sentence
                } else {
                    if (current.isNotBlank()) chunks += current.trim()
                    current = sentence
                }
            }
            if (current.isNotBlank()) chunks += current.trim()
        }
        return chunks
    }

    private fun directFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private fun directFloatBuffer(data: FloatArray): FloatBuffer =
        directFloatBuffer(data.size).apply {
            put(data)
            position(0)
        }

    private fun directLongBuffer(data: LongArray): LongBuffer =
        ByteBuffer.allocateDirect(data.size * Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()
            .apply {
                put(data)
                position(0)
            }

    private fun FloatBuffer.toFloatArray(): FloatArray =
        FloatArray(capacity()) { i -> get(i) }

    private fun writeFloatBuffer(buffer: FloatBuffer, data: FloatArray) {
        require(buffer.capacity() == data.size) {
            "DirectBuffer size mismatch: ${buffer.capacity()} != ${data.size}"
        }
        buffer.position(0)
        buffer.put(data)
        buffer.position(0)
    }

    private fun validateFiniteBuffer(stage: String, buffer: FloatBuffer) {
        for (i in 0 until buffer.capacity()) {
            if (!buffer.get(i).isFinite()) {
                error("$variantName $stage produced NaN/Inf at index $i")
            }
        }
    }

    private fun logTensorStatsBuffer(stage: String, buffer: FloatBuffer) {
        if (buffer.capacity() == 0) {
            Log.i(TAG, "[TENSOR-STATS] stage=$stage n=0")
            return
        }
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var sum = 0.0
        var sumSq = 0.0
        var absSum = 0.0
        var nonFinite = 0
        var clipped = 0
        var nearZero = 0
        for (i in 0 until buffer.capacity()) {
            val v = buffer.get(i)
            if (!v.isFinite()) {
                nonFinite++
                continue
            }
            if (v < min) min = v
            if (v > max) max = v
            val d = v.toDouble()
            sum += d
            sumSq += d * d
            absSum += kotlin.math.abs(d)
            if (kotlin.math.abs(d) >= 0.999) clipped++
            if (kotlin.math.abs(d) < 1.0e-6) nearZero++
        }
        val finiteN = (buffer.capacity() - nonFinite).coerceAtLeast(1)
        Log.i(
            TAG,
            "[TENSOR-STATS] stage=$stage n=${buffer.capacity()} nonfinite=$nonFinite " +
                "min=${String.format(Locale.US, "%.6g", min)} " +
                "max=${String.format(Locale.US, "%.6g", max)} " +
                "mean=${String.format(Locale.US, "%.6g", sum / finiteN)} " +
                "rms=${String.format(Locale.US, "%.6g", kotlin.math.sqrt(sumSq / finiteN))} " +
                "absmean=${String.format(Locale.US, "%.6g", absSum / finiteN)} " +
                "clip1=$clipped nearzero=$nearZero",
        )
    }

    private fun logTensorStats(stage: String, data: FloatArray) {
        if (data.isEmpty()) {
            Log.i(TAG, "[TENSOR-STATS] stage=$stage n=0")
            return
        }
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var sum = 0.0
        var sumSq = 0.0
        var absSum = 0.0
        var nonFinite = 0
        var clipped = 0
        var nearZero = 0
        for (v in data) {
            if (!v.isFinite()) {
                nonFinite++
                continue
            }
            if (v < min) min = v
            if (v > max) max = v
            val d = v.toDouble()
            sum += d
            sumSq += d * d
            absSum += kotlin.math.abs(d)
            if (kotlin.math.abs(d) >= 0.999) clipped++
            if (kotlin.math.abs(d) < 1.0e-6) nearZero++
        }
        val finiteN = (data.size - nonFinite).coerceAtLeast(1)
        val mean = sum / finiteN
        val rms = kotlin.math.sqrt(sumSq / finiteN)
        val absMean = absSum / finiteN
        Log.i(
            TAG,
            "[TENSOR-STATS] stage=$stage n=${data.size} nonfinite=$nonFinite " +
                "min=${String.format(Locale.US, "%.6g", min)} " +
                "max=${String.format(Locale.US, "%.6g", max)} " +
                "mean=${String.format(Locale.US, "%.6g", mean)} " +
                "rms=${String.format(Locale.US, "%.6g", rms)} " +
                "absmean=${String.format(Locale.US, "%.6g", absMean)} " +
                "clip1=$clipped nearzero=$nearZero",
        )
    }

    private fun validateFinite(stage: String, data: FloatArray) {
        val bad = data.indexOfFirst { !it.isFinite() }
        require(bad < 0) { "$variantName $stage produced NaN/Inf at index $bad" }
    }

    private fun floatToPcm16(wav: FloatArray, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        var o = 0
        for (i in 0 until count) {
            val v = wav[i].coerceIn(-1f, 1f)
            val s = (v * 32767f).roundToInt().coerceIn(-32768, 32767)
            out[o++] = (s and 0xff).toByte()
            out[o++] = ((s ushr 8) and 0xff).toByte()
        }
        return out
    }

    private fun finishDeepProfiling() {
        if (!config.enableDeepProfiler) return
        val sessions = synchronized(deepProfileSessions) { deepProfileSessions.toList() }
        sessions.forEach { it.finishProfiling() }
        Log.i(TAG, "[DEEP-PROFILER-FLUSH] sessions=${sessions.size} dir=${deepProfileRoot().absolutePath}")
    }

    private fun ensureNotStopped() {
        if (stopped.get()) throw InterruptedException("Supertone Original synthesis stopped")
    }

    private fun elapsedMs(startNs: Long): Double = (System.nanoTime() - startNs) / 1_000_000.0
    private fun formatMs(v: Double): String = String.format(Locale.US, "%.3f", v)

    override fun close() {
        stopped.set(true)
        if (vocoderDynamicLazy.isInitialized()) runCatching { vocoderDynamicLazy.value.close() }
        if (vectorDynamicLazy.isInitialized()) runCatching { vectorDynamicLazy.value.close() }
        if (encoderDynamicLazy.isInitialized()) runCatching { encoderDynamicLazy.value.close() }
        if (durationDynamicLazy.isInitialized()) runCatching { durationDynamicLazy.value.close() }
        closeSpecializedQnnSessions()
        synchronized(pregenRunners) {
            pregenRunners.forEach { runCatching { it.close() } }
            pregenRunners.clear()
        }
        if (w8a16DurationCpuSessionLazy.isInitialized()) runCatching { w8a16DurationCpuSessionLazy.value.close() }
        styleCache = null
        styleCacheId = null
        // OrtEnvironment.getEnvironment() is process-global; do not close it.
    }
}
