package audio.soniqo.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Downloads the supported Supertonic-3 bundles from q3146dq4 mirrors. */
object ModelManager {
    private const val TAG = "SupertonicTTS"
    private const val MODEL_SET_FILENAME = "model-set.txt"
    private const val SHA_MANIFEST_FILENAME = "model-sha256.txt"
    private const val MAX_RETRIES = 5
    private const val RETRY_DELAY_MS = 2000L

    private data class ModelFile(
        val path: String,
        val estimate: Long,
        val remotePaths: List<String> = listOf(path),
    )

    private data class ModelBundle(
        val baseUrl: String,
        val modelId: String,
        val modelVersion: Int,
        val dirName: String,
        val label: String,
        val layoutKey: String,
        val files: List<ModelFile>,
    )

    data class VerificationReport(
        val ok: Boolean,
        val checked: Int,
        val total: Int,
        val missing: List<String>,
        val mismatched: List<String>,
        val manifestInitialized: Boolean,
    )

    data class ModelRemovalReport(
        val label: String,
        val removedBytes: Long,
        val removedFiles: Int,
    )

    private enum class DownloadResult { SUCCESS, NOT_FOUND }

    private fun modelFile(
        path: String,
        estimate: Long,
        vararg remotePaths: String,
    ): ModelFile = ModelFile(
        path = path,
        estimate = estimate,
        remotePaths = if (remotePaths.isEmpty()) listOf(path) else remotePaths.toList(),
    )

    private val voiceEstimates = linkedMapOf(
        "F1" to 292_000L, "F2" to 292_000L, "F3" to 291_000L,
        "F4" to 292_000L, "F5" to 292_000L, "M1" to 292_000L,
        "M2" to 292_000L, "M3" to 290_000L, "M4" to 292_000L,
        "M5" to 292_000L,
    )

    private fun voiceFiles(vararg remotePrefixes: String): List<ModelFile> =
        voiceEstimates.map { (id, estimate) ->
            val local = "voice_styles/$id.json"
            val candidates = if (remotePrefixes.isEmpty()) {
                arrayOf(local)
            } else {
                remotePrefixes.map { prefix ->
                    val clean = prefix.trim('/')
                    if (clean.isEmpty()) local else "$clean/$local"
                }.toTypedArray()
            }
            modelFile(local, estimate, *candidates)
        }

    private val liteRt = ModelBundle(
        baseUrl = "https://huggingface.co/q3146dq4/Supertonic-3-LiteRT/resolve/main/",
        modelId = "q3146dq4/Supertonic-3-LiteRT@main",
        modelVersion = 1,
        dirName = "supertonic-3-soniqo-litert",
        label = "LiteRT FP32",
        layoutKey = "fixed-T128-L64-four-litert",
        files = listOf(
            modelFile("duration_predictor.tflite", 4_000_000L),
            modelFile("text_encoder.tflite", 37_000_000L),
            modelFile("vector_estimator.tflite", 245_000_000L),
            modelFile("vocoder.tflite", 101_000_000L),
            modelFile("tts.json", 20_000L),
            modelFile("unicode_indexer.json", 278_000L),
        ) + voiceFiles(),
    )

    private const val SONIQO_LITERT_BASE =
        "https://huggingface.co/q3146dq4/Supertonic-3-LiteRT/resolve/main/"
    private const val WI8_AFP32_BASE =
        "https://huggingface.co/q3146dq4/Supertonic-3-LiteRT-WI8-AFP32/resolve/main/"

    /**
     * Selective dynamic WI8-AFP32 derivative of the fixed T128/L64 LiteRT
     * bundle. Only the four TFLite graph files differ; tokenizer/config and
     * voice-style assets are unchanged and are fetched from the normal LiteRT
     * mirror when the WI8 repository does not carry them.
     */
    private val liteRtWi8Afp32 = ModelBundle(
        baseUrl = WI8_AFP32_BASE,
        modelId = "q3146dq4/Supertonic-3-LiteRT-WI8-AFP32@main",
        modelVersion = 1,
        dirName = "supertonic-3-litert-wi8-afp32",
        label = "LiteRT W8-AFP32",
        layoutKey = "fixed-T128-L64-selective-wi8-afp32-v1",
        files = listOf(
            modelFile("duration_predictor.tflite", 1_300_000L),
            modelFile("text_encoder.tflite", 36_000_000L),
            modelFile("vector_estimator.tflite", 67_000_000L),
            modelFile("vocoder.tflite", 36_000_000L),
            modelFile("tts.json", 20_000L, "${SONIQO_LITERT_BASE}tts.json"),
            modelFile("unicode_indexer.json", 278_000L, "${SONIQO_LITERT_BASE}unicode_indexer.json"),
        ) + voiceFiles(SONIQO_LITERT_BASE),
    )

    private const val MULTIPRESET_GELU_BASE =
        "https://huggingface.co/q3146dq4/Supertonic-3-LiteRT-Static-MultiPreset-GELU/resolve/main/"
    private const val MULTIPRESET_GELU_WI8_AFP32_BASE =
        "https://huggingface.co/q3146dq4/Supertonic-3-LiteRT-Static-MultiPreset-GELU-WI8-AFP32/resolve/main/"
    private const val OFFICIAL_SUPPORT_BASE =
        "https://huggingface.co/q3146dq4/supertonic-3/resolve/main/"

    /**
     * Independent static MultiPreset LiteRT bundle produced from the official
     * Supertonic-3 ONNX via fixed-shape specialization + exact GELU fusion +
     * litert-torch. This is intentionally a separate model identity from Soniqo.
     */
    private val liteRtStaticMultiPresetGelu = ModelBundle(
        baseUrl = MULTIPRESET_GELU_BASE,
        modelId = "q3146dq4/Supertonic-3-LiteRT-Static-MultiPreset-GELU@main",
        modelVersion = 1,
        dirName = "supertonic-3-litert-static-multipreset-gelu",
        label = "LiteRT Multi-P",
        layoutKey = "static-multisignature-gelu-T32-128-L32-128-v1",
        files = listOf(
            modelFile(
                "duration_predictor.tflite", 5_400_000L,
                "duration_predictor.tflite",
                "RESULT_GELU_AUTOBUCKET/duration_predictor.tflite",
            ),
            modelFile(
                "text_encoder.tflite", 40_000_000L,
                "text_encoder.tflite",
                "RESULT_GELU_AUTOBUCKET/text_encoder.tflite",
            ),
            modelFile(
                "vector_estimator.tflite", 291_000_000L,
                "vector_estimator.tflite",
                "RESULT_GELU_AUTOBUCKET/vector_estimator.tflite",
            ),
            modelFile(
                "vocoder.tflite", 103_000_000L,
                "vocoder.tflite",
                "RESULT_GELU_AUTOBUCKET/vocoder.tflite",
            ),
            modelFile(
                "multi_preset_manifest.json", 20_000L,
                "multi_preset_manifest.json",
                "RESULT_GELU_AUTOBUCKET/multi_preset_manifest.json",
                "manifest.json",
                "RESULT_GELU_AUTOBUCKET/manifest.json",
            ),
            // Tokenizer/config and voice styles are architecture assets, not
            // model weights. Prefer copies in the new repo, then reuse the
            // unchanged official Supertonic-3 mirror assets if omitted.
            modelFile(
                "tts.json", 20_000L,
                "tts.json",
                "${OFFICIAL_SUPPORT_BASE}onnx/tts.json",
            ),
            modelFile(
                "unicode_indexer.json", 278_000L,
                "unicode_indexer.json",
                "${OFFICIAL_SUPPORT_BASE}onnx/unicode_indexer.json",
            ),
        ) + voiceEstimates.map { (id, estimate) ->
            val local = "voice_styles/$id.json"
            modelFile(
                local,
                estimate,
                local,
                "${OFFICIAL_SUPPORT_BASE}$local",
            )
        },
    )


    /**
     * WI8-AFP32 quantized derivative of the same 7x7 static MultiPreset GELU
     * graph family. The runtime contract is intentionally identical to the
     * FP32 MultiPreset bundle: four multi-signature TFLite files plus the same
     * bucket manifest and architecture assets.
     */
    private val liteRtStaticMultiPresetGeluWi8Afp32 = ModelBundle(
        baseUrl = MULTIPRESET_GELU_WI8_AFP32_BASE,
        modelId = "q3146dq4/Supertonic-3-LiteRT-Static-MultiPreset-GELU-WI8-AFP32@main",
        modelVersion = 1,
        dirName = "supertonic-3-litert-static-multipreset-gelu-wi8-afp32",
        label = "LiteRT Multi-P W8-AFP32",
        layoutKey = "static-multisignature-gelu-T32-128-L32-128-wi8-afp32-v1",
        files = listOf(
            modelFile(
                "duration_predictor.tflite", 5_400_000L,
                "duration_predictor.tflite",
                "RESULT_GELU_AUTOBUCKET/duration_predictor.tflite",
            ),
            modelFile(
                "text_encoder.tflite", 40_000_000L,
                "text_encoder.tflite",
                "RESULT_GELU_AUTOBUCKET/text_encoder.tflite",
            ),
            modelFile(
                "vector_estimator.tflite", 160_000_000L,
                "vector_estimator.tflite",
                "RESULT_GELU_AUTOBUCKET/vector_estimator.tflite",
            ),
            modelFile(
                "vocoder.tflite", 60_000_000L,
                "vocoder.tflite",
                "RESULT_GELU_AUTOBUCKET/vocoder.tflite",
            ),
            modelFile(
                "multi_preset_manifest.json", 20_000L,
                "multi_preset_manifest.json",
                "RESULT_GELU_AUTOBUCKET/multi_preset_manifest.json",
                "manifest.json",
                "RESULT_GELU_AUTOBUCKET/manifest.json",
                "${MULTIPRESET_GELU_BASE}multi_preset_manifest.json",
            ),
            modelFile(
                "tts.json", 20_000L,
                "tts.json",
                "${MULTIPRESET_GELU_BASE}tts.json",
                "${OFFICIAL_SUPPORT_BASE}onnx/tts.json",
            ),
            modelFile(
                "unicode_indexer.json", 278_000L,
                "unicode_indexer.json",
                "${MULTIPRESET_GELU_BASE}unicode_indexer.json",
                "${OFFICIAL_SUPPORT_BASE}onnx/unicode_indexer.json",
            ),
        ) + voiceEstimates.map { (id, estimate) ->
            val local = "voice_styles/$id.json"
            modelFile(
                local,
                estimate,
                local,
                "${MULTIPRESET_GELU_BASE}$local",
                "${OFFICIAL_SUPPORT_BASE}$local",
            )
        },
    )

    // Keep the old local directory so users who already downloaded the official
    // FP32 ONNX bundle do not need to download ~398 MB again just because the
    // source URL moved to the q3146dq4 mirror.
    private val onnxFp32 = ModelBundle(
        baseUrl = "https://huggingface.co/q3146dq4/supertonic-3/resolve/main/",
        modelId = "q3146dq4/supertonic-3@main",
        modelVersion = 1,
        dirName = "supertonic-3-supertone-original-onnx",
        label = "ONNX FP32",
        layoutKey = "official-onnx-fp32-dynamic",
        files = listOf(
            modelFile("duration_predictor.onnx", 3_700_000L, "onnx/duration_predictor.onnx"),
            modelFile("text_encoder.onnx", 36_400_000L, "onnx/text_encoder.onnx"),
            modelFile("vector_estimator.onnx", 257_000_000L, "onnx/vector_estimator.onnx"),
            modelFile("vocoder.onnx", 101_000_000L, "onnx/vocoder.onnx"),
            modelFile("tts.json", 9_000L, "onnx/tts.json"),
            modelFile("unicode_indexer.json", 278_000L, "onnx/unicode_indexer.json"),
        ) + voiceFiles(),
    )


    /**
     * User-calibrated V7/BEST_STRICT_W8A16 bundle for Qualcomm QNN HTP.
     * The HF repository keeps the canonical drop-in layout under onnx/ and
     * shares the normal voice_styles/ directory.
     */
    private val onnxW8A16Qdq = ModelBundle(
        baseUrl = "https://huggingface.co/q3146dq4/supertonic-3-w8a16-qnn-qdq/resolve/main/",
        modelId = "q3146dq4/supertonic-3-w8a16-qnn-qdq@main:v7",
        modelVersion = 1,
        dirName = "supertonic-3-w8a16-qnn-qdq",
        label = "ONNX W8A16",
        layoutKey = "v7-best-strict-w8a16-qnn-static-qdq",
        files = listOf(
            modelFile("duration_predictor.onnx", 2_100_000L, "onnx/duration_predictor.onnx", "duration_predictor.onnx"),
            modelFile("text_encoder.onnx", 13_000_000L, "onnx/text_encoder.onnx", "text_encoder.onnx"),
            modelFile("vector_estimator.onnx", 68_000_000L, "onnx/vector_estimator.onnx", "vector_estimator.onnx"),
            modelFile("vocoder.onnx", 27_000_000L, "onnx/vocoder.onnx", "vocoder.onnx"),
            modelFile("tts.json", 9_000L, "onnx/tts.json", "tts.json"),
            modelFile("unicode_indexer.json", 278_000L, "onnx/unicode_indexer.json", "unicode_indexer.json"),
        ) + voiceFiles(),
    )

    /** Remove retired ONNX bundles after custom-voice migration has run. */
    fun cleanupRetiredModels(context: Context) {
        listOf(
            "supertonic-3-onnx-int8" to "ONNX INT8",
            "supertonic-3-onnx-fp16" to "ONNX FP16",
        ).forEach { (dirName, label) ->
            val retired = File(context.filesDir, dirName)
            if (!retired.exists()) return@forEach
            if (runCatching { retired.deleteRecursively() }.getOrDefault(false)) {
                Log.i(TAG, "Removed retired Supertonic-3 $label bundle: ${retired.absolutePath}")
            } else {
                Log.w(TAG, "Could not fully remove retired Supertonic-3 $label bundle: ${retired.absolutePath}")
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun bundle(model: TtsModel): ModelBundle = when (model) {
        TtsModel.SUPERTONIC -> liteRt
        TtsModel.SUPERTONIC_LITERT_WI8_AFP32 -> liteRtWi8Afp32
        TtsModel.SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU -> liteRtStaticMultiPresetGelu
        TtsModel.SUPERTONIC_LITERT_STATIC_MULTIPRESET_GELU_WI8_AFP32 ->
            liteRtStaticMultiPresetGeluWi8Afp32
        TtsModel.SUPERTONIC_ORIGINAL_ONNX -> onnxFp32
        TtsModel.SUPERTONIC_ONNX_W8A16_QDQ -> onnxW8A16Qdq
    }

    fun modelLabel(model: TtsModel): String = bundle(model).label

    fun modelDir(
        context: Context,
        model: TtsModel = TtsModel.SUPERTONIC,
    ): File = File(context.filesDir, bundle(model).dirName)

    /**
     * REV22: all Supertonic-3 variants use the same style_ttl/style_dp contract.
     * Keep user-imported voices in one app-wide library and mirror them into each
     * model's legacy voice_styles directory so the native LiteRT and ONNX runners
     * can keep their existing file lookup without making users re-import per model.
     */
    fun sharedCustomVoiceDir(context: Context): File =
        File(context.filesDir, "supertonic-3-custom-voices").apply { mkdirs() }

    private fun isCustomVoiceFile(file: File): Boolean =
        file.isFile &&
            file.extension.equals("json", ignoreCase = true) &&
            file.nameWithoutExtension !in voiceEstimates.keys

    fun customVoiceFiles(context: Context): List<File> =
        sharedCustomVoiceDir(context).listFiles()
            ?.filter(::isCustomVoiceFile)
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    fun migrateAndSyncCustomVoices(context: Context) {
        val shared = sharedCustomVoiceDir(context)
        val bundles = listOf(
            liteRt,
            liteRtWi8Afp32,
            liteRtStaticMultiPresetGelu,
            liteRtStaticMultiPresetGeluWi8Afp32,
            onnxFp32,
            onnxW8A16Qdq,
        )

        // REV24 retires the broken FP16 runtime, but preserve any custom voices
        // imported into its legacy directory before cleanup removes that bundle.
        val legacyFp16Voices = File(context.filesDir, "supertonic-3-onnx-fp16/voice_styles")
        legacyFp16Voices.listFiles()?.filter(::isCustomVoiceFile)?.forEach { src ->
            val dst = File(shared, src.name)
            if (!dst.isFile || dst.length() != src.length() || src.lastModified() > dst.lastModified()) {
                runCatching { src.copyTo(dst, overwrite = true) }
                    .onFailure { Log.w(TAG, "Legacy FP16 custom voice migration failed: ${src.name}: ${it.message}") }
            }
        }

        // Preserve existing per-model imports from REV21 and earlier.
        for (spec in bundles) {
            val local = File(context.filesDir, spec.dirName).resolve("voice_styles")
            local.listFiles()?.filter(::isCustomVoiceFile)?.forEach { src ->
                val dst = File(shared, src.name)
                if (!dst.isFile || dst.length() != src.length() || src.lastModified() > dst.lastModified()) {
                    runCatching { src.copyTo(dst, overwrite = true) }
                        .onFailure { Log.w(TAG, "Custom voice migration failed: ${src.name}: ${it.message}") }
                }
            }
        }
        syncSharedCustomVoices(context)
    }

    fun syncSharedCustomVoices(context: Context, model: TtsModel) {
        val sharedFiles = customVoiceFiles(context)
        val sharedNames = sharedFiles.mapTo(hashSetOf()) { it.name }
        val local = File(modelDir(context, model), "voice_styles").apply { mkdirs() }

        sharedFiles.forEach { src ->
            val dst = File(local, src.name)
            if (!dst.isFile || dst.length() != src.length() || dst.lastModified() < src.lastModified()) {
                runCatching { src.copyTo(dst, overwrite = true) }
                    .onFailure { Log.w(TAG, "Custom voice sync failed: ${src.name}: ${it.message}") }
            }
        }

        // Shared library is authoritative after migration. Deleting a voice once
        // removes the per-model mirrors as well. Built-in F*/M* JSON is untouched.
        local.listFiles()?.filter(::isCustomVoiceFile)?.forEach { stale ->
            if (stale.name !in sharedNames) runCatching { stale.delete() }
        }
    }

    fun syncSharedCustomVoices(context: Context) {
        TtsModel.entries.forEach { syncSharedCustomVoices(context, it) }
    }

    fun removeSharedCustomVoice(context: Context, voiceId: String): Boolean {
        val fileName = "$voiceId.json"
        val deleted = runCatching { File(sharedCustomVoiceDir(context), fileName).delete() }.getOrDefault(false)
        TtsModel.entries.forEach { model ->
            runCatching { File(modelDir(context, model), "voice_styles/$fileName").delete() }
        }
        return deleted
    }

    fun estimatedSizeBytes(
        model: TtsModel = TtsModel.SUPERTONIC,
    ): Long = bundle(model).files.sumOf { it.estimate }

    /** Bytes currently occupied by one downloaded (or partially downloaded) bundle. */
    fun installedSizeBytes(
        context: Context,
        model: TtsModel = TtsModel.SUPERTONIC,
    ): Long {
        val dir = modelDir(context, model)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { file ->
            if (!file.isFile) return@filter false
            val relative = file.relativeTo(dir).invariantSeparatorsPath
            val isCustomVoiceMirror =
                relative.startsWith("voice_styles/") && isCustomVoiceFile(file)
            !isCustomVoiceMirror
        }.sumOf { it.length() }
    }

    /**
     * Removes only [model]. User-imported voices are migrated to the app-wide
     * shared voice directory before the per-model mirror is deleted.
     */
    suspend fun removeTtsModel(
        context: Context,
        model: TtsModel,
    ): ModelRemovalReport = withContext(Dispatchers.IO) {
        migrateAndSyncCustomVoices(context)
        val spec = bundle(model)
        val dir = modelDir(context, model)
        val files = if (dir.exists()) dir.walkTopDown().count { it.isFile } else 0
        val bytes = installedSizeBytes(context, model)
        if (dir.exists() && !dir.deleteRecursively()) {
            throw IOException("Could not completely remove ${spec.label} from ${dir.absolutePath}")
        }
        Log.i(TAG, "Removed model bundle: ${spec.label}; files=$files bytes=$bytes")
        ModelRemovalReport(spec.label, bytes, files)
    }

    fun areTtsModelsReady(
        context: Context,
        model: TtsModel = TtsModel.SUPERTONIC,
    ): Boolean {
        val spec = bundle(model)
        val dir = modelDir(context, model)
        val version = File(dir, "version.txt")
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.toIntOrNull() ?: 0
        val set = File(dir, MODEL_SET_FILENAME)
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
        if (version != spec.modelVersion || set != modelSetKey(spec)) return false
        return spec.files.all { valid(File(dir, it.path)) }
    }

    suspend fun verifyTtsModels(
        context: Context,
        model: TtsModel = TtsModel.SUPERTONIC,
        onProgress: ((checked: Int, total: Int, file: String) -> Unit)? = null,
    ): VerificationReport = withContext(Dispatchers.IO) {
        val spec = bundle(model)
        val dir = modelDir(context, model)
        val files = spec.files
        val total = files.size
        val missing = files.filterNot { valid(File(dir, it.path)) }.map { it.path }
        if (missing.isNotEmpty()) {
            return@withContext VerificationReport(
                ok = false, checked = 0, total = total, missing = missing,
                mismatched = emptyList(), manifestInitialized = false,
            )
        }

        val manifestFile = File(dir, SHA_MANIFEST_FILENAME)
        val expected = readShaManifest(manifestFile)
        val initialize = expected.isEmpty()
        val actual = linkedMapOf<String, String>()
        val mismatched = mutableListOf<String>()
        files.forEachIndexed { index, modelFile ->
            onProgress?.invoke(index, total, modelFile.path)
            val digest = sha256(File(dir, modelFile.path))
            actual[modelFile.path] = digest
            if (!initialize && expected[modelFile.path] != digest) mismatched += modelFile.path
            onProgress?.invoke(index + 1, total, modelFile.path)
        }
        if (initialize) {
            writeShaManifest(manifestFile, actual)
            Log.i(TAG, "Initialized local SHA-256 manifest for ${spec.modelId}: ${files.size} files")
        }
        VerificationReport(
            ok = mismatched.isEmpty(),
            checked = total,
            total = total,
            missing = emptyList(),
            mismatched = mismatched,
            manifestInitialized = initialize,
        )
    }

    suspend fun ensureTtsModels(
        context: Context,
        model: TtsModel = TtsModel.SUPERTONIC,
        onProgress: ((completed: Long, total: Long, file: String) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val spec = bundle(model)
        val dir = modelDir(context, model)
        dir.mkdirs()

        val versionFile = File(dir, "version.txt")
        val setFile = File(dir, MODEL_SET_FILENAME)
        val currentVersion = versionFile.takeIf { it.exists() }
            ?.readText()?.trim()?.toIntOrNull() ?: 0
        val currentSet = setFile.takeIf { it.exists() }?.readText()?.trim()
        val expectedSet = modelSetKey(spec)
        val allFilesAlreadyPresent = spec.files.all { valid(File(dir, it.path)) }

        // Mirror-only migration: preserve existing bytes and merely update the
        // source identity. This avoids re-downloading hundreds of MB after the
        // repository URL switches from upstream to q3146dq4.
        if (currentVersion == spec.modelVersion && allFilesAlreadyPresent) {
            if (currentSet != expectedSet) {
                Log.i(TAG, "Model source migrated without redownload: ${spec.modelId}")
                setFile.writeText(expectedSet)
            }
            syncSharedCustomVoices(context, model)
            return@withContext dir.absolutePath
        }

        // A real bundle schema/version change is the only reason to discard all
        // files. A mirror change or interrupted download keeps valid files.
        if (currentVersion != 0 && currentVersion != spec.modelVersion) {
            dir.listFiles()?.forEach { it.deleteRecursively() }
            dir.mkdirs()
        }

        val pending = spec.files.filterNot { valid(File(dir, it.path)) }
        val total = pending.sumOf { it.estimate }.coerceAtLeast(1L)
        var completed = 0L
        onProgress?.invoke(0L, total, "Preparing")

        for (file in pending) {
            val dest = File(dir, file.path)
            dest.parentFile?.mkdirs()
            var downloaded = false
            var attempted = emptyList<String>()

            for (remotePath in file.remotePaths.distinct()) {
                val url = if (
                    remotePath.startsWith("https://") || remotePath.startsWith("http://")
                ) remotePath else spec.baseUrl + remotePath
                attempted = attempted + remotePath
                Log.i(TAG, "Downloading $url -> ${file.path}")
                when (download(url, dest) { bytes, actualTotal ->
                    val fileTotal = maxOf(actualTotal, file.estimate)
                    onProgress?.invoke(
                        (completed + bytes).coerceAtMost(total),
                        total,
                        file.path,
                    )
                    if (fileTotal > 0 && bytes == fileTotal) {
                        onProgress?.invoke(
                            (completed + fileTotal).coerceAtMost(total),
                            total,
                            file.path,
                        )
                    }
                }) {
                    DownloadResult.SUCCESS -> {
                        downloaded = true
                        break
                    }
                    DownloadResult.NOT_FOUND -> {
                        Log.w(TAG, "Model candidate not found: $url")
                    }
                }
            }

            if (!downloaded) {
                throw IOException(
                    "${spec.label}: '${file.path}' not found in mirror. Tried: ${attempted.joinToString()}"
                )
            }
            completed =
                (completed + maxOf(dest.length(), file.estimate)).coerceAtMost(total)
        }

        versionFile.writeText(spec.modelVersion.toString())
        setFile.writeText(expectedSet)

        if (!areTtsModelsReady(context, model)) {
            throw IOException("${spec.label} model verification failed. Tap retry.")
        }
        // New or repaired downloads establish a byte-level local integrity baseline.
        // Existing installations are not forced to re-download; tapping Verify Model Files
        // initializes the same SHA-256 manifest from their current complete bundle once.
        writeShaManifest(
            File(dir, SHA_MANIFEST_FILENAME),
            spec.files.associateTo(linkedMapOf()) { it.path to sha256(File(dir, it.path)) },
        )
        syncSharedCustomVoices(context, model)
        dir.absolutePath
    }

    private fun modelSetKey(spec: ModelBundle): String =
        "v${spec.modelVersion}|${spec.modelId}|${spec.layoutKey}"

    private fun valid(file: File): Boolean =
        file.exists() && file.isFile && file.length() >= 1024L

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun readShaManifest(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val split = trimmed.indexOf("  ")
            if (split <= 0) return@mapNotNull null
            val hash = trimmed.substring(0, split).lowercase()
            val path = trimmed.substring(split + 2)
            if (hash.length != 64 || path.isBlank()) null else path to hash
        }.toMap()
    }

    private fun writeShaManifest(file: File, hashes: Map<String, String>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(
            buildString {
                append("# Supertonic model local SHA-256 integrity manifest\n")
                hashes.toSortedMap().forEach { (path, hash) ->
                    append(hash).append("  ").append(path).append('\n')
                }
            },
            Charsets.UTF_8,
        )
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun download(
        url: String,
        dest: File,
        onBytes: (Long, Long) -> Unit,
    ): DownloadResult {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        var last: IOException? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                val existing = if (tmp.exists()) tmp.length() else 0L
                val request = Request.Builder().url(url).apply {
                    if (existing > 0L) header("Range", "bytes=$existing-")
                }.build()

                client.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        tmp.delete()
                        return DownloadResult.NOT_FOUND
                    }
                    if (response.code == 416 && existing > 0L) {
                        tmp.delete()
                        throw IOException("HTTP 416 after stale partial download for $url")
                    }
                    if (!response.isSuccessful && response.code != 206) {
                        throw IOException("HTTP ${response.code} for $url")
                    }
                    val body =
                        response.body ?: throw IOException("Empty response for $url")
                    val length = body.contentLength()
                    val range = response.header("Content-Range")
                    val actualTotal = if (response.code == 206) {
                        range?.substringAfterLast('/')?.toLongOrNull()
                            ?: (existing + length).coerceAtLeast(existing)
                    } else {
                        length
                    }

                    val append = response.code == 206 && existing > 0L
                    if (!append && existing > 0L) tmp.delete()

                    FileOutputStream(tmp, append).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            var done = if (append) existing else 0L
                            onBytes(done, actualTotal)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                done += count
                                onBytes(done, actualTotal)
                            }
                        }
                    }

                    if (actualTotal > 0L && tmp.length() != actualTotal) {
                        throw IOException(
                            "Incomplete download: ${tmp.length()} / $actualTotal"
                        )
                    }

                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                    return DownloadResult.SUCCESS
                }
            } catch (e: IOException) {
                last = e
                Log.e(
                    TAG,
                    "Download attempt $attempt failed: ${dest.name}: ${e.message}"
                )
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(RETRY_DELAY_MS * attempt)
                }
            }
        }

        throw IOException(
            "Download failed after $MAX_RETRIES attempts: ${last?.message}",
            last,
        )
    }
}
