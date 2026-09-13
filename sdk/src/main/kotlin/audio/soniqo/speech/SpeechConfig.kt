package audio.soniqo.speech

import android.util.Log

enum class TtsModel(internal val nativeId: Int) {
    SUPERTONIC(1),
    SUPERTONIC_ORIGINAL_ONNX(4),
    // New calibrated QNN static-QDQ bundle. Use a fresh native id instead of
    // reusing retired SUPERTONIC_ONNX_INT8(6).
    SUPERTONIC_ONNX_W8A16_QDQ(7),
    // Independent LiteRT re-export from the official Supertonic-3 ONNX.
    // 7x7 static T/L signatures + exact GELU fusion; not a Soniqo-derived model.
    SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU(8),
    // Selective dynamic weight-int8 / activation-fp32 derivative of the
    // fixed-shape Soniqo-compatible LiteRT four-graph bundle.
    SUPERTONIC_LITERT_WI8_AFP32(9),
    // Weight-int8 / activation-fp32 quantized derivative of the 7x7 static
    // MultiPreset GELU bundle. Same signature/bucket contract as id 8.
    SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU_WI8_AFP32(10);

    val isOnnx: Boolean
        get() = this == SUPERTONIC_ORIGINAL_ONNX ||
            this == SUPERTONIC_ONNX_W8A16_QDQ

    val isLiteRt: Boolean
        get() = !isOnnx
}

enum class InferenceBackend(internal val nativeId: Int) {
    // LiteRT native XNNPACK CPU. Never use this identity for ONNX settings.
    CPU_XNNPACK(0),
    // ONNX Runtime default CPU Execution Provider. The native id is unused by
    // ONNX, but remains distinct so persisted settings/logs are semantically honest.
    CPU_ORT(5),
    // ONNX-only explicit XNNPACK EP experiment. Never passed to the LiteRT native runtime.
    ONNX_XNNPACK(4),
    // Same published FP32 .tflite files, but eligible XNNPACK FP32 operators
    // are executed with the delegate's forced-FP16 path. Kept separate from
    // the verified FP32 CPU baseline.
    CPU_XNNPACK_FP16(3),
    // Qualcomm accelerator. ONNX keeps ORT QNN HTP; LiteRT Multi-P FP32 may
    // use the QNN 2.47 selected-signature preview on Android 12+ Snapdragon.
    QUALCOMM_NPU(2);

    internal val isNativeCpu: Boolean
        get() = this == CPU_XNNPACK || this == CPU_XNNPACK_FP16
}

data class SpeechSynthesizerConfig(
    val modelDir: String = "",
    val useNnapi: Boolean = false,
    val backend: InferenceBackend = InferenceBackend.CPU_XNNPACK,
    val ttsModel: TtsModel = TtsModel.SUPERTONIC,
    val voiceId: String = "F1",
    val speed: Float = 1.0f,
    val totalSteps: Int = 4,
    val numThreads: Int = 4,
    val chunkCap: Int = 0,
    val preGenerationQueue: Int = 1,
    val chunkGapMinMs: Int = 0,
    val chunkGapMaxMs: Int = 250,
    val trailingSilenceTrimMs: Int = 220,
    /** Android's extracted native-library directory (applicationInfo.nativeLibraryDir). */
    val nativeLibraryDir: String = "",
    /** Writable cache directory used for LiteRT GPU/QNN on-device compilation. */
    val acceleratorCacheDir: String = "",
    /** Opt-in deep profiler. OFF for normal RTF tests because profiling adds overhead. */
    val enableDeepProfiler: Boolean = false,
    /** Legacy UI compatibility value; REV24 NPU uses adaptive T buckets up to 160. */
    val originalFixedTextT: Int = 160,
    /** Legacy UI compatibility value; REV24 NPU uses adaptive L buckets up to 192. */
    val originalFixedLatentL: Int = 192,
)

data class SpeechSynthesisResult(
    val sampleRate: Int,
    val pcm16: ByteArray,
    val profile: String = "",
)

data class QnnCachePreGenProgress(
    val model: TtsModel,
    val completed: Int,
    val total: Int,
    val graph: String,
    val shape: String,
    val contextState: String,
)

data class QnnCachePreGenSummary(
    val model: TtsModel,
    val total: Int,
    val generated: Int,
    val hits: Int,
    val notWritten: Int,
)

interface SpeechSynthesizer : AutoCloseable {
    val sampleRate: Int
    fun synthesize(text: String, language: String = "en"): SpeechSynthesisResult
    fun synthesizeStreaming(text: String, language: String = "en", onChunk: (ByteArray, Boolean) -> Unit)
    fun setVoice(voiceId: String)
    fun setSpeed(speed: Float)
    fun setTotalSteps(totalSteps: Int)
    fun setChunkCap(chunkCap: Int)
    fun setPreGeneration(enabled: Boolean)
    fun setPreGenerationQueue(depth: Int)
    fun setChunkGap(minMs: Int, maxMs: Int)
    fun setTrailingSilenceTrimMs(ms: Int)
    fun lastProfile(): String
    fun preGenerateQnnContexts(onProgress: (QnnCachePreGenProgress) -> Unit): QnnCachePreGenSummary
    fun stop()
    companion object {
        operator fun invoke(config: SpeechSynthesizerConfig): SpeechSynthesizer = SpeechSynthesizerImpl(config)
        fun expectedQnnContextCount(model: TtsModel): Int = OnnxSupertonicRunner.expectedQnnContextCount(model)
    }
}

internal class SpeechSynthesizerImpl(
    private val config: SpeechSynthesizerConfig,
) : SpeechSynthesizer {
    companion object {
        private const val TAG = "SupertonicSynth"

        // Keep the app-bundled QAIRT runtime ahead of older vendor copies.
        // SM6350/lito is a pre-HTP generation device: its validated direct
        // QAIRT path is libQnnHta.so. Modern Snapdragon devices retain HTP.
        @Volatile private var qualcommRuntimePreloaded = false

        private fun isSm6350HtaDevice(): Boolean {
            val soc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                android.os.Build.SOC_MODEL else ""
            val haystack = listOf(
                soc, android.os.Build.BOARD, android.os.Build.HARDWARE,
                android.os.Build.DEVICE, android.os.Build.PRODUCT,
            ).joinToString(" ").lowercase()
            return haystack.contains("sm6350") || Regex("\\blito\\b").containsMatchIn(haystack)
        }

        private fun preloadQualcommRuntime() {
            if (qualcommRuntimePreloaded) return
            synchronized(SpeechSynthesizerImpl::class.java) {
                if (qualcommRuntimePreloaded) return
                val hta = isSm6350HtaDevice()
                Log.i(
                    TAG,
                    if (hta) "Preloading APK Qualcomm runtime: QNN 2.47.0, QnnSystem -> QnnHta (SM6350)"
                    else "Preloading APK Qualcomm runtime: QNN 2.47.0, QnnSystem -> QnnHtp",
                )
                System.loadLibrary("QnnSystem")
                System.loadLibrary(if (hta) "QnnHta" else "QnnHtp")
                qualcommRuntimePreloaded = true
                Log.i(TAG, "APK Qualcomm runtime preload complete")
            }
        }
    }

    // LiteRT CPU remains the default. FP32 Multi-P alone exposes the new
    // Qualcomm QNN 2.47 selected-signature preview. ONNX remains fail-fast;
    // the LiteRT preview retries the same request on CPU and reports the
    // changed active backend explicitly.
    @Volatile private var onnxRunner: OnnxSupertonicRunner? = null
    @Volatile private var handle: Long = 0L
    @Volatile private var activeBackend = InferenceBackend.CPU_XNNPACK
    @Volatile private var fallbackReason: String? = null

    private var appliedVoice = config.voiceId
    private var appliedSpeed = config.speed.coerceIn(0.25f, 3.0f)
    private var appliedSteps = config.totalSteps.coerceIn(1, 64)
    private var appliedChunkCap = if (config.chunkCap <= 0) 0 else config.chunkCap.coerceIn(24, 96)
    private var appliedPreGeneration = false
    private var appliedPreGenerationQueue = 1
    private var appliedGapMin = config.chunkGapMinMs.coerceIn(0, 2000)
    private var appliedGapMax = config.chunkGapMaxMs.coerceIn(0, 2000).coerceAtLeast(appliedGapMin)
    private var appliedTrailingTrim = config.trailingSilenceTrimMs.coerceIn(0, 500)

    init {
        Log.i(TAG, "[MODEL-BACKEND] model=${config.ttsModel.name} requested=${config.backend.name} dir=${config.modelDir}")
        try {
            installBackend(config.backend)
        } catch (t: Throwable) {
            if (config.ttsModel.isOnnx ||
                config.backend == InferenceBackend.CPU_XNNPACK ||
                (config.backend == InferenceBackend.QUALCOMM_NPU && config.ttsModel.isOnnx)
            ) {
                if (config.backend == InferenceBackend.QUALCOMM_NPU) {
                    Log.e(TAG, "[NPU-FAIL-FAST] accelerator init failed; CPU fallback disabled", t)
                }
                throw t
            }
            fallbackReason = "${config.backend.name} init failed: ${messageOf(t)}"
            Log.w(TAG, "Accelerator init failed; using CPU/XNNPACK", t)
            installBackend(InferenceBackend.CPU_XNNPACK)
        }
    }

    private fun installBackend(backend: InferenceBackend) {
        require(
            !(config.ttsModel == TtsModel.SUPERTONIC && backend == InferenceBackend.QUALCOMM_NPU)
        ) {
            "Qualcomm NPU support for the Soniqo LiteRT bundle was removed in REV23 after reproducible vector_estimator NaN/Inf corruption"
        }
        var madeHandle = 0L
        try {
            if (config.ttsModel.isOnnx) {
                if (config.ttsModel == TtsModel.SUPERTONIC_ONNX_W8A16_QDQ) {
                    require(
                        backend == InferenceBackend.CPU_ORT ||
                            backend == InferenceBackend.CPU_XNNPACK || // legacy in-memory compatibility
                            backend == InferenceBackend.ONNX_XNNPACK ||
                            backend == InferenceBackend.QUALCOMM_NPU
                    ) {
                        "Supertonic-3 ONNX W8A16 QDQ backends are CPU, CPU XNN, or Qualcomm NPU (HTP/HTA)"
                    }
                    Log.i(
                        TAG,
                        "[W8A16-QDQ] calibrated static QDQ bundle; CPU=diagnostic/reference, NPU=ORT QNN HTP/HTA",
                    )
                }
                if (backend == InferenceBackend.QUALCOMM_NPU) {
                    preloadQualcommRuntime()
                }
                // FP32 / W8A16-QDQ ONNX variants share one end-to-end ORT
                // orchestration path. Modern Qualcomm uses QNN HTP hybrid;
                // SM6350/lito uses strict QNN HTA sessions.
                val onnx = OnnxSupertonicRunner(config.copy(backend = backend))
                onnxRunner = onnx
                activeBackend = backend
                handle = 0L
                Log.i(TAG, "[ONNX-DIRECT] model=${config.ttsModel.name} backend=${backend.name} ${onnx.backendReport()}")
                return
            } else {
                val liteRtQnnPreview =
                    config.ttsModel == TtsModel.SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU &&
                        backend == InferenceBackend.QUALCOMM_NPU
                require(backend.isNativeCpu || liteRtQnnPreview) {
                    "LiteRT supports CPU/XNNPACK; Qualcomm NPU preview is limited to Multi-P FP32"
                }
                if (liteRtQnnPreview) preloadQualcommRuntime()
                // Load the packaged 16 KB-compatible LiteRT runtime explicitly.
                // This also preserves the original linker error instead of
                // poisoning NativeBridge's class initializer for the process.
                NativeBridge.ensureLoaded(config.nativeLibraryDir)
            }
            madeHandle = NativeBridge.nativeCreateSynthesizer(
                config.modelDir,
                config.useNnapi,
                backend.nativeId,
                config.ttsModel.nativeId,
                appliedVoice,
                appliedSteps,
                appliedSpeed,
                config.numThreads,
                appliedChunkCap,
                config.nativeLibraryDir,
                config.acceleratorCacheDir,
                config.enableDeepProfiler,
                null,
            )
            check(madeHandle != 0L) { "Supertonic native engine could not be created" }
            applyRuntimeSettings(madeHandle)
            activeBackend = backend
            handle = madeHandle
        } catch (t: Throwable) {
            if (madeHandle != 0L) runCatching { NativeBridge.nativeDestroySynthesizer(madeHandle) }
            throw t
        }
    }

    private fun applyRuntimeSettings(target: Long) {
        NativeBridge.nativeSetSynthesizerVoice(target, appliedVoice)
        NativeBridge.nativeSetSynthesizerSpeed(target, appliedSpeed)
        NativeBridge.nativeSetSynthesizerSteps(target, appliedSteps)
        NativeBridge.nativeSetSynthesizerChunkCap(target, appliedChunkCap)
        NativeBridge.nativeSetSynthesizerPreGeneration(target, appliedPreGeneration)
        NativeBridge.nativeSetSynthesizerPreGenerationQueue(target, appliedPreGenerationQueue)
        NativeBridge.nativeSetSynthesizerChunkGap(target, appliedGapMin, appliedGapMax)
        NativeBridge.nativeSetSynthesizerTrailingSilenceTrim(target, appliedTrailingTrim)
    }

    private fun fallbackToCpu(failure: Throwable) {
        val failedBackend = activeBackend
        if (failedBackend == InferenceBackend.CPU_XNNPACK) throw failure
        if (failedBackend == InferenceBackend.QUALCOMM_NPU && config.ttsModel.isOnnx) {
            fallbackReason = "${failedBackend.name} rejected: ${messageOf(failure)}"
            Log.e(
                TAG,
                "[NPU-FAIL-FAST] accelerator output rejected; CPU fallback disabled so NPU failures stay visible",
                failure,
            )
            throw failure
        }
        fallbackReason = "${failedBackend.name} rejected: ${messageOf(failure)}"
        Log.w(TAG, "Accelerator output rejected; recreating on CPU/XNNPACK", failure)

        val oldHandle = handle
        handle = 0L
        if (oldHandle != 0L) runCatching { NativeBridge.nativeDestroySynthesizer(oldHandle) }
        activeBackend = InferenceBackend.CPU_XNNPACK
        installBackend(InferenceBackend.CPU_XNNPACK)
    }

    private fun messageOf(t: Throwable): String =
        (t.message ?: t.javaClass.simpleName).replace(';', ',').replace('=', ':').replace('\n', ' ')

    private fun decoratedProfile(): String {
        val native = if (handle != 0L) NativeBridge.nativeGetLastProfile(handle) else ""
        val onnx = onnxRunner?.lastProfile().orEmpty()
        return buildString {
            if (native.isNotBlank()) append(native).append(';')
            if (onnx.isNotBlank()) append(onnx).append(';')
            append("model=").append(config.ttsModel.name)
            append(";requested_backend=").append(config.backend.name)
            append(";active_backend=").append(activeBackend.name)
            append(";accelerator_fallback=").append(fallbackReason ?: "none")
        }
    }

    override val sampleRate: Int
        get() = onnxRunner?.sampleRate
            ?: if (handle != 0L) NativeBridge.nativeSynthesizerSampleRate(handle) else 0

    override fun synthesize(text: String, language: String): SpeechSynthesisResult {
        onnxRunner?.let { onnx ->
            val pcm = onnx.synthesize(text, language, appliedVoice, appliedSteps, appliedSpeed)
            return SpeechSynthesisResult(sampleRate, pcm, decoratedProfile())
        }
        val pcm = try {
            NativeBridge.nativeSynthesize(handle, text, language)
        } catch (t: Throwable) {
            fallbackToCpu(t)
            NativeBridge.nativeSynthesize(handle, text, language)
        }
        return SpeechSynthesisResult(sampleRate, pcm, decoratedProfile())
    }

    override fun synthesizeStreaming(text: String, language: String, onChunk: (ByteArray, Boolean) -> Unit) {
        onnxRunner?.let { onnx ->
            onnx.synthesizeStreaming(
                text = text,
                language = language,
                voiceId = appliedVoice,
                totalSteps = appliedSteps,
                speed = appliedSpeed,
                preGeneration = appliedPreGeneration,
                preGenerationQueue = appliedPreGenerationQueue,
                chunkGapMinMs = appliedGapMin,
                chunkGapMaxMs = appliedGapMax,
                trailingSilenceTrimMs = appliedTrailingTrim,
                onChunk = onChunk,
            )
            return
        }
        var emittedAudio = false
        val callback = NativeBridge.SynthesisCallback { audio, final ->
            if (audio.isNotEmpty()) emittedAudio = true
            onChunk(audio, final)
        }
        try {
            NativeBridge.nativeSynthesizeStreaming(handle, text, language, callback)
        } catch (t: Throwable) {
            if (emittedAudio || activeBackend == InferenceBackend.CPU_XNNPACK) throw t
            fallbackToCpu(t)
            NativeBridge.nativeSynthesizeStreaming(handle, text, language, callback)
        }
    }

    override fun setVoice(voiceId: String) {
        appliedVoice = voiceId
        if (handle != 0L) NativeBridge.nativeSetSynthesizerVoice(handle, voiceId)
    }

    override fun setSpeed(speed: Float) {
        appliedSpeed = speed.coerceIn(0.25f, 3.0f)
        if (handle != 0L) NativeBridge.nativeSetSynthesizerSpeed(handle, appliedSpeed)
    }

    override fun setTotalSteps(totalSteps: Int) {
        appliedSteps = totalSteps.coerceIn(1, 64)
        if (handle != 0L) NativeBridge.nativeSetSynthesizerSteps(handle, appliedSteps)
    }

    override fun setChunkCap(chunkCap: Int) {
        // 0 = automatic token/L-window chunking. Positive values are retained
        // only for internal diagnostics/benchmarks.
        appliedChunkCap = if (chunkCap <= 0) 0 else chunkCap.coerceIn(24, 96)
        if (handle != 0L) NativeBridge.nativeSetSynthesizerChunkCap(handle, appliedChunkCap)
    }

    override fun setPreGeneration(enabled: Boolean) {
        val effective = enabled && activeBackend != InferenceBackend.QUALCOMM_NPU
        appliedPreGeneration = effective
        if (enabled && !effective) Log.i(TAG, "[PREGEN-AUTO-OFF] backend=${activeBackend.name} reason=npu-fast-path")
        if (handle != 0L) NativeBridge.nativeSetSynthesizerPreGeneration(handle, effective)
    }

    override fun setPreGenerationQueue(depth: Int) {
        appliedPreGenerationQueue = 1
        if (handle != 0L) NativeBridge.nativeSetSynthesizerPreGenerationQueue(handle, appliedPreGenerationQueue)
    }

    override fun setChunkGap(minMs: Int, maxMs: Int) {
        appliedGapMin = minMs.coerceIn(0, 2000)
        appliedGapMax = maxMs.coerceIn(0, 2000).coerceAtLeast(appliedGapMin)
        if (handle != 0L) NativeBridge.nativeSetSynthesizerChunkGap(handle, appliedGapMin, appliedGapMax)
    }

    override fun setTrailingSilenceTrimMs(ms: Int) {
        appliedTrailingTrim = ms.coerceIn(0, 500)
        if (handle != 0L) NativeBridge.nativeSetSynthesizerTrailingSilenceTrim(handle, appliedTrailingTrim)
    }

    override fun lastProfile(): String = decoratedProfile()

    override fun preGenerateQnnContexts(
        onProgress: (QnnCachePreGenProgress) -> Unit,
    ): QnnCachePreGenSummary {
        require(activeBackend == InferenceBackend.QUALCOMM_NPU && config.ttsModel.isOnnx) {
            "QNN cache pre-generation requires an ONNX model on Qualcomm NPU"
        }
        val runner = onnxRunner ?: error("ONNX runner is not initialized")
        val summary = runner.preGenerateAllQnnContexts { progress ->
            onProgress(
                QnnCachePreGenProgress(
                    model = config.ttsModel,
                    completed = progress.completed,
                    total = progress.total,
                    graph = progress.graph,
                    shape = progress.shape,
                    contextState = progress.contextState,
                )
            )
        }
        return QnnCachePreGenSummary(
            model = config.ttsModel,
            total = summary.total,
            generated = summary.generated,
            hits = summary.hits,
            notWritten = summary.notWritten,
        )
    }

    override fun stop() {
        onnxRunner?.stop()
        val current = handle
        if (current != 0L) NativeBridge.nativeStopSynthesizer(current)
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) NativeBridge.nativeDestroySynthesizer(current)
        onnxRunner?.close()
        onnxRunner = null
    }
}
