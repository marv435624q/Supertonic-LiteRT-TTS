package audio.soniqo.speech

import android.content.Context
import android.os.Build

object TtsSettings {
    private const val PREFS = "supertonic_tts"
    private const val KEY_VOICE = "voice"
    private const val KEY_SPEED = "speed"
    private const val KEY_STEPS = "steps"
    private const val KEY_ALLOW_NA = "allow_na"
    private const val KEY_TEST_LANGUAGE = "test_language"
    private const val KEY_THREADS = "threads"
    private const val KEY_BACKEND = "backend"
    private const val KEY_TTS_MODEL = "tts_model"
    private const val RETIRED_ONNX_INT8_MODEL = "SUPERTONIC_ONNX_INT8"
    private const val RETIRED_ONNX_FP16_MODEL = "SUPERTONIC_ONNX_FP16"
    private val RETIRED_ACCELERATOR_BACKENDS = setOf("GPU_LITERT", "NNAPI_DEVICE")
    private const val KEY_CPU_DEFAULT_MIGRATION = "cpu_default_backend_migration_20260818_delegate_v3"
    private const val KEY_CHUNK_MODE = "chunk_mode"
    private const val KEY_CHUNK_CAP = "chunk_cap"
    private const val KEY_PREGEN = "pre_generation"
    private const val KEY_PREGEN_QUEUE = "pre_generation_queue"
    private const val KEY_GAP_MIN = "chunk_gap_min_ms"
    private const val KEY_GAP_MAX = "chunk_gap_max_ms"
    private const val KEY_TRAILING_TRIM = "trailing_silence_trim_ms"
    private const val KEY_INTERNAL_SILENCE = "internal_silence_compression"
    private const val KEY_INTERNAL_SILENCE_MAX = "internal_silence_max_pause_ms"
    private const val KEY_ORIGINAL_SHAPE_PRESET = "original_shape_preset"
    private const val KEY_ORIGINAL_FIXED_T = "original_fixed_t"
    private const val KEY_ORIGINAL_FIXED_L = "original_fixed_l"
    private const val KEY_DEEP_PROFILER = "deep_profiler"

    const val CHUNK_CONSERVATIVE = "conservative"
    const val CHUNK_BALANCED = "balanced"
    const val CHUNK_LONG = "long"
    const val CHUNK_MANUAL = "manual"

    const val MIN_CHUNK_CAP = 24
    const val MAX_CHUNK_CAP = 96
    const val MIN_GAP_MS = 0
    const val MAX_GAP_MS = 2000
    const val DEFAULT_GAP_MIN_MS = 0
    const val DEFAULT_GAP_MAX_MS = 250
    const val MIN_TRAILING_TRIM_MS = 0
    const val MAX_TRAILING_TRIM_MS = 500
    const val DEFAULT_TRAILING_TRIM_MS = 0
    const val MIN_INTERNAL_SILENCE_MAX_MS = 100
    const val MAX_INTERNAL_SILENCE_MAX_MS = 500
    const val DEFAULT_INTERNAL_SILENCE_MAX_MS = 200

    const val ORIGINAL_SHAPE_SONIQO = "soniqo_128_64"
    const val ORIGINAL_SHAPE_FAST = "fast_160_128"
    const val ORIGINAL_SHAPE_BALANCED = "balanced_160_160"
    const val ORIGINAL_SHAPE_LONG = "long_160_192"
    const val ORIGINAL_SHAPE_EXTRA_LONG = "extra_long_192_192"
    const val ORIGINAL_SHAPE_CUSTOM = "custom"
    const val DEFAULT_ORIGINAL_SHAPE_PRESET = ORIGINAL_SHAPE_LONG
    const val MIN_ORIGINAL_T = 64
    const val MAX_ORIGINAL_T = 256
    const val MIN_ORIGINAL_L = 64
    const val MAX_ORIGINAL_L = 256

    fun voice(context: Context): String = prefs(context).getString(KEY_VOICE, "F1") ?: "F1"
    fun speed(context: Context): Float = prefs(context).getFloat(KEY_SPEED, 1.0f)
    fun steps(context: Context): Int = prefs(context).getInt(KEY_STEPS, 4)
    fun allowNa(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW_NA, false)
    fun testLanguage(context: Context): String = prefs(context).getString(KEY_TEST_LANGUAGE, "na") ?: "na"
    fun threads(context: Context): Int = prefs(context).getInt(KEY_THREADS, 4)
    private fun backendKey(model: TtsModel): String = "${KEY_BACKEND}_${model.name}"

    private fun isSm6350Device(): Boolean {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        val haystack = listOf(
            soc, Build.BOARD, Build.HARDWARE, Build.DEVICE, Build.PRODUCT,
        ).joinToString(" ").lowercase()
        return haystack.contains("sm6350") || Regex("\\blito\\b").containsMatchIn(haystack)
    }

    private fun defaultBackendForModel(model: TtsModel): InferenceBackend =
        if (model.isOnnx) InferenceBackend.CPU_ORT else InferenceBackend.CPU_XNNPACK

    private fun normalizeBackendForModel(
        model: TtsModel,
        backend: InferenceBackend,
    ): InferenceBackend {
        val cpuDefault = defaultBackendForModel(model)
        if (backend.name in RETIRED_ACCELERATOR_BACKENDS) return cpuDefault
        // Migrate the historical shared CPU_XNNPACK identity to the honest ONNX
        // Runtime CPU EP identity. LiteRT keeps CPU_XNNPACK.
        if (model.isOnnx && backend == InferenceBackend.CPU_XNNPACK) {
            return InferenceBackend.CPU_ORT
        }
        if (model.isLiteRt && !backend.isNativeCpu) return InferenceBackend.CPU_XNNPACK
        if (isSm6350Device() && backend == InferenceBackend.QUALCOMM_NPU) {
            return cpuDefault
        }
        return backend
    }

    fun backend(context: Context, model: TtsModel): InferenceBackend {
        val p = prefs(context)
        val key = backendKey(model)

        // Migrate the legacy single global backend only for the model that was
        // selected at the time of migration. Every other model starts from the
        // stable CPU baseline and then persists independently.
        val cpuDefault = defaultBackendForModel(model)
        val stored = if (p.contains(key)) {
            p.getString(key, cpuDefault.name)
        } else {
            val legacy = if (
                model == ttsModel(context) &&
                p.contains(KEY_BACKEND)
            ) {
                p.getString(KEY_BACKEND, cpuDefault.name)
            } else {
                cpuDefault.name
            }
            val parsedLegacy = runCatching {
                InferenceBackend.valueOf(legacy ?: cpuDefault.name)
            }.getOrDefault(cpuDefault)
            val normalizedLegacy = normalizeBackendForModel(model, parsedLegacy)
            check(
                p.edit()
                    .putString(key, normalizedLegacy.name)
                    .putBoolean(KEY_CPU_DEFAULT_MIGRATION, true)
                    .commit()
            ) { "Failed to migrate per-model backend setting for ${model.name}" }
            return normalizedLegacy
        } ?: cpuDefault.name

        val parsed = runCatching { InferenceBackend.valueOf(stored) }
            .getOrDefault(cpuDefault)
        val normalized = normalizeBackendForModel(model, parsed)
        if (normalized != parsed) {
            check(p.edit().putString(key, normalized.name).commit()) {
                "Failed to normalize backend setting for ${model.name}"
            }
        }
        return normalized
    }

    fun backend(context: Context): InferenceBackend = backend(context, ttsModel(context))

    fun ttsModel(context: Context): TtsModel {
        val p = prefs(context)
        val stored = p.getString(KEY_TTS_MODEL, TtsModel.SUPERTONIC.name)
            ?: TtsModel.SUPERTONIC.name
        if (stored == RETIRED_ONNX_INT8_MODEL || stored == RETIRED_ONNX_FP16_MODEL) {
            val replacement = TtsModel.SUPERTONIC_ORIGINAL_ONNX
            check(p.edit().putString(KEY_TTS_MODEL, replacement.name).commit()) {
                "Failed to migrate retired ONNX model setting: $stored"
            }
            return replacement
        }
        return runCatching { TtsModel.valueOf(stored) }.getOrDefault(TtsModel.SUPERTONIC)
    }

    fun chunkMode(context: Context): String = prefs(context).getString(KEY_CHUNK_MODE, CHUNK_BALANCED) ?: CHUNK_BALANCED
    fun manualChunkCap(context: Context): Int = prefs(context).getInt(KEY_CHUNK_CAP, 64).coerceIn(MIN_CHUNK_CAP, MAX_CHUNK_CAP)
    fun preGeneration(context: Context): Boolean = prefs(context).getBoolean(KEY_PREGEN, false)
    fun preGenerationQueue(context: Context): Int = 1
    fun chunkGapMinMs(context: Context): Int = prefs(context).getInt(KEY_GAP_MIN, DEFAULT_GAP_MIN_MS).coerceIn(MIN_GAP_MS, MAX_GAP_MS)
    fun chunkGapMaxMs(context: Context): Int = prefs(context).getInt(KEY_GAP_MAX, DEFAULT_GAP_MAX_MS).coerceIn(MIN_GAP_MS, MAX_GAP_MS).coerceAtLeast(chunkGapMinMs(context))
    fun trailingSilenceTrimMs(context: Context): Int = prefs(context).getInt(KEY_TRAILING_TRIM, DEFAULT_TRAILING_TRIM_MS).coerceIn(MIN_TRAILING_TRIM_MS, MAX_TRAILING_TRIM_MS)
    fun internalSilenceCompression(context: Context): Boolean = prefs(context).getBoolean(KEY_INTERNAL_SILENCE, false)
    fun internalSilenceMaxPauseMs(context: Context): Int = prefs(context)
        .getInt(KEY_INTERNAL_SILENCE_MAX, DEFAULT_INTERNAL_SILENCE_MAX_MS)
        .coerceIn(MIN_INTERNAL_SILENCE_MAX_MS, MAX_INTERNAL_SILENCE_MAX_MS)
    fun deepProfiler(context: Context): Boolean = prefs(context).getBoolean(KEY_DEEP_PROFILER, false)


    fun originalShapePreset(context: Context): String = normalizeOriginalShapePreset(
        prefs(context).getString(KEY_ORIGINAL_SHAPE_PRESET, DEFAULT_ORIGINAL_SHAPE_PRESET)
            ?: DEFAULT_ORIGINAL_SHAPE_PRESET
    )

    fun originalFixedT(context: Context): Int {
        val preset = originalShapePreset(context)
        return if (preset == ORIGINAL_SHAPE_CUSTOM) {
            prefs(context).getInt(KEY_ORIGINAL_FIXED_T, 160).coerceIn(MIN_ORIGINAL_T, MAX_ORIGINAL_T)
        } else originalPresetValues(preset).first
    }

    fun originalFixedL(context: Context): Int {
        val preset = originalShapePreset(context)
        return if (preset == ORIGINAL_SHAPE_CUSTOM) {
            prefs(context).getInt(KEY_ORIGINAL_FIXED_L, 192).coerceIn(MIN_ORIGINAL_L, MAX_ORIGINAL_L)
        } else originalPresetValues(preset).second
    }

    fun originalPresetValues(preset: String): Pair<Int, Int> = when (normalizeOriginalShapePreset(preset)) {
        ORIGINAL_SHAPE_SONIQO -> 128 to 64
        ORIGINAL_SHAPE_FAST -> 160 to 128
        ORIGINAL_SHAPE_LONG -> 160 to 192
        ORIGINAL_SHAPE_EXTRA_LONG -> 192 to 192
        ORIGINAL_SHAPE_CUSTOM -> 160 to 192
        else -> 160 to 160
    }

    fun setOriginalShapeSettings(context: Context, preset: String, fixedT: Int, fixedL: Int) {
        check(prefs(context).edit()
            .putString(KEY_ORIGINAL_SHAPE_PRESET, normalizeOriginalShapePreset(preset))
            .putInt(KEY_ORIGINAL_FIXED_T, fixedT.coerceIn(MIN_ORIGINAL_T, MAX_ORIGINAL_T))
            .putInt(KEY_ORIGINAL_FIXED_L, fixedL.coerceIn(MIN_ORIGINAL_L, MAX_ORIGINAL_L))
            .commit()) { "Failed to persist ONNX fixed-shape settings" }
    }

    /** Manual LiteRT chunk sizing is retired. 0 means token/L-window AutoBucket chunking. */
    fun chunkCap(context: Context): Int = 0

    fun save(
        context: Context,
        voice: String,
        speed: Float,
        steps: Int,
        threads: Int = threads(context),
        chunkMode: String = chunkMode(context),
        manualChunkCap: Int = manualChunkCap(context),
        backend: InferenceBackend = backend(context),
    ) {
        // System TTS can be invoked immediately after leaving this app. Commit synchronously
        // so the TextToSpeechService sees the new settings without a disk-write race.
        val model = ttsModel(context)
        val normalizedBackend = normalizeBackendForModel(model, backend)
        check(prefs(context).edit()
            .putString(KEY_VOICE, voice)
            .putFloat(KEY_SPEED, speed)
            .putInt(KEY_STEPS, steps)
            .putInt(KEY_THREADS, threads.coerceIn(1, 64))
            .putString(KEY_BACKEND, normalizedBackend.name)
            .putString(backendKey(model), normalizedBackend.name)
            .putString(KEY_CHUNK_MODE, normalizeChunkMode(chunkMode))
            .putInt(KEY_CHUNK_CAP, manualChunkCap.coerceIn(MIN_CHUNK_CAP, MAX_CHUNK_CAP))
            .putBoolean(KEY_PREGEN, prefs(context).getBoolean(KEY_PREGEN, false))
            .putBoolean(KEY_ALLOW_NA, prefs(context).getBoolean(KEY_ALLOW_NA, false))
            .commit()) { "Failed to persist Supertonic TTS settings" }
    }


    fun setTtsModel(context: Context, model: TtsModel) {
        check(
            prefs(context).edit()
                .putString(KEY_TTS_MODEL, model.name)
                .commit()
        ) {
            "Failed to persist Supertonic model setting"
        }
    }

    fun setBackend(context: Context, model: TtsModel, backend: InferenceBackend) {
        val normalized = normalizeBackendForModel(model, backend)
        val editor = prefs(context).edit()
            .putString(backendKey(model), normalized.name)
            .putBoolean(KEY_CPU_DEFAULT_MIGRATION, true)
        // Keep the legacy key in sync only for the currently selected model so
        // older service/build variants do not see a stale unrelated backend.
        if (model == ttsModel(context)) {
            editor.putString(KEY_BACKEND, normalized.name)
        }
        check(editor.commit()) {
            "Failed to persist backend setting for ${model.name}"
        }
    }

    fun setBackend(context: Context, backend: InferenceBackend) {
        setBackend(context, ttsModel(context), backend)
    }

    fun setChunkSettings(context: Context, mode: String, manualCap: Int) {
        check(prefs(context).edit()
            .putString(KEY_CHUNK_MODE, normalizeChunkMode(mode))
            .putInt(KEY_CHUNK_CAP, manualCap.coerceIn(MIN_CHUNK_CAP, MAX_CHUNK_CAP))
            .commit()) { "Failed to persist Supertonic chunk settings" }
    }


    fun setPreGeneration(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_PREGEN, enabled).commit()) {
            "Failed to persist Supertonic pre-generation setting"
        }
    }

    fun setStreamingControls(context: Context, pregenQueue: Int, gapMinMs: Int, gapMaxMs: Int, trailingTrimMs: Int) {
        val minGap = gapMinMs.coerceIn(MIN_GAP_MS, MAX_GAP_MS)
        val maxGap = gapMaxMs.coerceIn(MIN_GAP_MS, MAX_GAP_MS).coerceAtLeast(minGap)
        check(prefs(context).edit()
            .putInt(KEY_PREGEN_QUEUE, 1)
            .putInt(KEY_GAP_MIN, minGap)
            .putInt(KEY_GAP_MAX, maxGap)
            .putInt(KEY_TRAILING_TRIM, trailingTrimMs.coerceIn(MIN_TRAILING_TRIM_MS, MAX_TRAILING_TRIM_MS))
            .commit()) { "Failed to persist Supertonic streaming controls" }
    }

    fun setInternalSilenceControls(context: Context, enabled: Boolean, maxPauseMs: Int) {
        check(prefs(context).edit()
            .putBoolean(KEY_INTERNAL_SILENCE, enabled)
            .putInt(KEY_INTERNAL_SILENCE_MAX, maxPauseMs.coerceIn(MIN_INTERNAL_SILENCE_MAX_MS, MAX_INTERNAL_SILENCE_MAX_MS))
            .commit()) { "Failed to persist internal silence controls" }
    }

    fun setDeepProfiler(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_DEEP_PROFILER, enabled).commit()) {
            "Failed to persist deep profiler setting"
        }
    }

    fun setAllowNa(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean(KEY_ALLOW_NA, enabled).commit()) {
            "Failed to persist Supertonic mixed-language setting"
        }
    }

    fun setTestLanguage(context: Context, language: String) {
        val normalized = language.lowercase().ifBlank { "na" }
        check(prefs(context).edit().putString(KEY_TEST_LANGUAGE, normalized).commit()) {
            "Failed to persist Supertonic test language"
        }
    }

    private fun normalizeOriginalShapePreset(preset: String): String = when (preset) {
        ORIGINAL_SHAPE_SONIQO, ORIGINAL_SHAPE_FAST, ORIGINAL_SHAPE_BALANCED,
        ORIGINAL_SHAPE_LONG, ORIGINAL_SHAPE_EXTRA_LONG, ORIGINAL_SHAPE_CUSTOM -> preset
        else -> DEFAULT_ORIGINAL_SHAPE_PRESET
    }

    private fun normalizeChunkMode(mode: String): String = when (mode) {
        CHUNK_CONSERVATIVE, CHUNK_BALANCED, CHUNK_LONG, CHUNK_MANUAL -> mode
        else -> CHUNK_BALANCED
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
