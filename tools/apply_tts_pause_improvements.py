#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one anchor, found {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def create_or_verify(path: str, content: str) -> None:
    p = ROOT / path
    if p.exists():
        if p.read_text(encoding="utf-8") != content:
            raise RuntimeError(f"{path}: exists with unexpected content")
        return
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")


# ---------------------------------------------------------------------------
# Stateful native-1.0x internal silence compressor.
# ---------------------------------------------------------------------------
compressor = r'''package audio.soniqo.speech.audio

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Caps only long *internal* silence runs in mono PCM16.
 *
 * This processor intentionally runs on the model's native 1.0x PCM before Sonic.
 * Leading and trailing silence are preserved; only silence bracketed by speech is
 * eligible for compression.  State is retained across streaming chunks so a pause
 * split at a chunk boundary is treated as one continuous run.
 */
class InternalSilenceCompressor(
    sampleRate: Int,
    maxPauseMs: Int,
    private val rmsThreshold: Double = DEFAULT_RMS_THRESHOLD,
) {
    companion object {
        const val FRAME_MS = 10
        /** Roughly -42 dBFS for PCM16; only sustained low-energy regions are silence. */
        const val DEFAULT_RMS_THRESHOLD = 256.0
    }

    private val sampleRate = sampleRate.coerceAtLeast(1)
    private val frameSamples = (this.sampleRate * FRAME_MS / 1000).coerceAtLeast(1)
    private val frameBytes = frameSamples * 2
    private val maxPauseSamples =
        (this.sampleRate.toLong() * maxPauseMs.coerceIn(100, 500) / 1000L).coerceAtLeast(1L)

    private var carry = ByteArray(0)
    private val pendingSilence = ByteArrayOutputStream()
    private var pendingSilenceSamples = 0L
    private var seenSpeech = false

    var inputSamples: Long = 0L
        private set
    var outputSamples: Long = 0L
        private set
    var compressedRuns: Int = 0
        private set
    var removedSamples: Long = 0L
        private set

    val removedMs: Double
        get() = removedSamples * 1000.0 / sampleRate

    fun process(pcm16: ByteArray, final: Boolean = false): ByteArray {
        require(pcm16.size % 2 == 0) { "PCM16 must contain complete samples" }
        inputSamples += pcm16.size / 2L

        val data = if (carry.isEmpty()) pcm16 else ByteArray(carry.size + pcm16.size).also {
            carry.copyInto(it, 0)
            pcm16.copyInto(it, carry.size)
        }
        val completeBytes = data.size / frameBytes * frameBytes
        val out = ByteArrayOutputStream(data.size)

        var offset = 0
        while (offset < completeBytes) {
            val silent = isSilent(data, offset, frameBytes)
            if (silent) {
                if (seenSpeech) {
                    pendingSilence.write(data, offset, frameBytes)
                    pendingSilenceSamples += frameSamples.toLong()
                } else {
                    out.write(data, offset, frameBytes)
                }
            } else {
                if (pendingSilenceSamples > 0L) flushInternalSilence(out)
                out.write(data, offset, frameBytes)
                seenSpeech = true
            }
            offset += frameBytes
        }
        carry = if (completeBytes < data.size) data.copyOfRange(completeBytes, data.size) else ByteArray(0)

        if (final) {
            // A partial final frame belongs to the utterance tail. If it is speech,
            // the preceding pending silence was internal; otherwise preserve it as
            // trailing silence and let the existing Silence Trim control own the tail.
            if (carry.isNotEmpty()) {
                if (seenSpeech && !isSilent(carry, 0, carry.size)) {
                    if (pendingSilenceSamples > 0L) flushInternalSilence(out)
                    out.write(carry)
                    seenSpeech = true
                } else if (seenSpeech) {
                    pendingSilence.write(carry)
                    pendingSilenceSamples += carry.size / 2L
                } else {
                    out.write(carry)
                }
                carry = ByteArray(0)
            }
            if (pendingSilenceSamples > 0L) flushPendingRaw(out)
        }

        val bytes = out.toByteArray()
        outputSamples += bytes.size / 2L
        return bytes
    }

    private fun flushInternalSilence(out: ByteArrayOutputStream) {
        val bytes = pendingSilence.toByteArray()
        val totalSamples = pendingSilenceSamples
        if (totalSamples <= maxPauseSamples) {
            out.write(bytes)
        } else {
            val keepBytes = (maxPauseSamples * 2L).coerceAtMost(bytes.size.toLong()).toInt() and -2
            val firstBytes = (keepBytes / 2) and -2
            val lastBytes = keepBytes - firstBytes
            if (firstBytes > 0) out.write(bytes, 0, firstBytes)
            if (lastBytes > 0) out.write(bytes, bytes.size - lastBytes, lastBytes)
            removedSamples += totalSamples - keepBytes / 2L
            compressedRuns++
        }
        pendingSilence.reset()
        pendingSilenceSamples = 0L
    }

    private fun flushPendingRaw(out: ByteArrayOutputStream) {
        pendingSilence.writeTo(out)
        pendingSilence.reset()
        pendingSilenceSamples = 0L
    }

    private fun isSilent(bytes: ByteArray, offset: Int, length: Int): Boolean {
        val samples = length / 2
        if (samples <= 0) return true
        var sumSquares = 0.0
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val lo = bytes[i].toInt() and 0xff
            val hi = bytes[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sumSquares += sample.toDouble() * sample.toDouble()
            i += 2
        }
        return sqrt(sumSquares / samples) <= rmsThreshold
    }
}
'''
create_or_verify(
    "sdk/src/main/kotlin/audio/soniqo/speech/audio/InternalSilenceCompressor.kt",
    compressor,
)

# ---------------------------------------------------------------------------
# Persistent settings for internal-silence compression.
# ---------------------------------------------------------------------------
settings = "sdk/src/main/kotlin/audio/soniqo/speech/TtsSettings.kt"
replace_once(
    settings,
    '    private const val KEY_TRAILING_TRIM = "trailing_silence_trim_ms"\n',
    '    private const val KEY_TRAILING_TRIM = "trailing_silence_trim_ms"\n'
    '    private const val KEY_INTERNAL_SILENCE = "internal_silence_compression"\n'
    '    private const val KEY_INTERNAL_SILENCE_MAX = "internal_silence_max_pause_ms"\n',
)
replace_once(
    settings,
    '    const val DEFAULT_TRAILING_TRIM_MS = 0\n',
    '    const val DEFAULT_TRAILING_TRIM_MS = 0\n'
    '    const val MIN_INTERNAL_SILENCE_MAX_MS = 100\n'
    '    const val MAX_INTERNAL_SILENCE_MAX_MS = 500\n'
    '    const val DEFAULT_INTERNAL_SILENCE_MAX_MS = 200\n',
)
replace_once(
    settings,
    '    fun trailingSilenceTrimMs(context: Context): Int = prefs(context).getInt(KEY_TRAILING_TRIM, DEFAULT_TRAILING_TRIM_MS).coerceIn(MIN_TRAILING_TRIM_MS, MAX_TRAILING_TRIM_MS)\n    fun deepProfiler(context: Context): Boolean = prefs(context).getBoolean(KEY_DEEP_PROFILER, false)\n',
    '    fun trailingSilenceTrimMs(context: Context): Int = prefs(context).getInt(KEY_TRAILING_TRIM, DEFAULT_TRAILING_TRIM_MS).coerceIn(MIN_TRAILING_TRIM_MS, MAX_TRAILING_TRIM_MS)\n'
    '    fun internalSilenceCompression(context: Context): Boolean = prefs(context).getBoolean(KEY_INTERNAL_SILENCE, false)\n'
    '    fun internalSilenceMaxPauseMs(context: Context): Int = prefs(context)\n'
    '        .getInt(KEY_INTERNAL_SILENCE_MAX, DEFAULT_INTERNAL_SILENCE_MAX_MS)\n'
    '        .coerceIn(MIN_INTERNAL_SILENCE_MAX_MS, MAX_INTERNAL_SILENCE_MAX_MS)\n'
    '    fun deepProfiler(context: Context): Boolean = prefs(context).getBoolean(KEY_DEEP_PROFILER, false)\n',
)
replace_once(
    settings,
    '    fun setDeepProfiler(context: Context, enabled: Boolean) {\n',
    '    fun setInternalSilenceControls(context: Context, enabled: Boolean, maxPauseMs: Int) {\n'
    '        check(prefs(context).edit()\n'
    '            .putBoolean(KEY_INTERNAL_SILENCE, enabled)\n'
    '            .putInt(KEY_INTERNAL_SILENCE_MAX, maxPauseMs.coerceIn(MIN_INTERNAL_SILENCE_MAX_MS, MAX_INTERNAL_SILENCE_MAX_MS))\n'
    '            .commit()) { "Failed to persist internal silence controls" }\n'
    '    }\n\n'
    '    fun setDeepProfiler(context: Context, enabled: Boolean) {\n',
)

# ---------------------------------------------------------------------------
# Seed whitespace-normalization regexes into the existing editable rule list.
# They are seeded once only; deleting/disabling/editing them is persistent.
# ---------------------------------------------------------------------------
rules = "sdk/src/main/kotlin/audio/soniqo/speech/rules/PronunciationRules.kt"
replace_once(
    rules,
    '    private const val KEY_RULES = "rules_json"\n',
    '    private const val KEY_RULES = "rules_json"\n'
    '    private const val KEY_BUILTIN_WHITESPACE_V1 = "builtin_whitespace_v1_seeded"\n',
)
replace_once(
    rules,
    '    fun load(context: Context): List<Rule> {\n        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RULES, "[]") ?: "[]"\n        return parse(raw)\n    }\n',
    '''    fun load(context: Context): List<Rule> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RULES, "[]") ?: "[]"
        val existing = parse(raw)
        if (prefs.getBoolean(KEY_BUILTIN_WHITESPACE_V1, false)) return existing

        val seeded = existing.toMutableList()
        val existingKeys = seeded.mapTo(hashSetOf()) { key(it) }
        for (rule in builtinWhitespaceRules()) {
            if (key(rule) !in existingKeys) seeded += rule
        }
        check(prefs.edit()
            .putString(KEY_RULES, toJson(seeded).toString())
            .putBoolean(KEY_BUILTIN_WHITESPACE_V1, true)
            .commit()) { "Failed to seed built-in whitespace rules" }
        return seeded
    }

    private fun builtinWhitespaceRules(): List<Rule> = listOf(
        Rule(term = "[\\r\\n\\t]+", replacement = " ", ignoreCase = false, isRegex = true),
        Rule(term = "[\\u00A0\\u2007\\u202F]+", replacement = " ", ignoreCase = false, isRegex = true),
        Rule(term = " {2,}", replacement = " ", ignoreCase = false, isRegex = true),
    )
''',
)

# ---------------------------------------------------------------------------
# Sonic post-process: honor Android pitch without ever changing model speed.
# ---------------------------------------------------------------------------
speed = "sdk/src/main/kotlin/audio/soniqo/speech/audio/AudioSpeedProcessor.kt"
replace_once(
    speed,
    '    class Stream(sampleRate: Int, speed: Float) {\n',
    '    class Stream(sampleRate: Int, speed: Float, pitch: Float = 1.0f) {\n',
)
replace_once(
    speed,
    '            sonic.setSpeed(clamped)\n            sonic.setPitch(1.0f)\n',
    '            sonic.setSpeed(clamped)\n            sonic.setPitch(pitch.coerceIn(0.25f, 4.0f))\n',
)
replace_once(
    speed,
    '    fun apply(pcm16: ByteArray, sampleRate: Int, speed: Float): Result {\n        val start = System.nanoTime()\n        val clamped = speed.coerceIn(0.25f, 3.0f)\n        if (pcm16.isEmpty() || kotlin.math.abs(clamped - 1.0f) < 0.001f) {\n            return Result(pcm16, (System.nanoTime() - start) / 1_000_000.0)\n        }\n        val stream = Stream(sampleRate, clamped)\n',
    '    fun apply(pcm16: ByteArray, sampleRate: Int, speed: Float, pitch: Float = 1.0f): Result {\n'
    '        val start = System.nanoTime()\n'
    '        val clamped = speed.coerceIn(0.25f, 3.0f)\n'
    '        val clampedPitch = pitch.coerceIn(0.25f, 4.0f)\n'
    '        if (pcm16.isEmpty() || (kotlin.math.abs(clamped - 1.0f) < 0.001f && kotlin.math.abs(clampedPitch - 1.0f) < 0.001f)) {\n'
    '            return Result(pcm16, (System.nanoTime() - start) / 1_000_000.0)\n'
    '        }\n'
    '        val stream = Stream(sampleRate, clamped, clampedPitch)\n',
)

# ---------------------------------------------------------------------------
# System TextToSpeechService: warm engine, latency markers, pitch, and the
# 1.0x native PCM -> internal-silence -> Sonic pipeline.
# ---------------------------------------------------------------------------
service = "sdk/src/main/kotlin/audio/soniqo/speech/service/SpeechTextToSpeechService.kt"
replace_once(
    service,
    'import audio.soniqo.speech.audio.AudioSpeedProcessor\n',
    'import audio.soniqo.speech.audio.AudioSpeedProcessor\nimport audio.soniqo.speech.audio.InternalSilenceCompressor\n',
)
replace_once(
    service,
    'import java.util.Locale\n',
    'import java.util.Locale\nimport java.util.concurrent.atomic.AtomicLong\n',
)
replace_once(
    service,
    '    @Volatile private var loadedDeepProfiler = false\n',
    '    @Volatile private var loadedDeepProfiler = false\n'
    '    @Volatile private var serviceDestroyed = false\n'
    '    @Volatile private var warmThread: Thread? = null\n'
    '    private val requestSequence = AtomicLong(0L)\n',
)
replace_once(
    service,
    '        synthesisWakeLock = pm.newWakeLock(\n            PowerManager.PARTIAL_WAKE_LOCK,\n            "${packageName}:TTS-Synthesis"\n        ).apply {\n            setReferenceCounted(false)\n        }\n    }\n',
    '        synthesisWakeLock = pm.newWakeLock(\n'
    '            PowerManager.PARTIAL_WAKE_LOCK,\n'
    '            "${packageName}:TTS-Synthesis"\n'
    '        ).apply {\n'
    '            setReferenceCounted(false)\n'
    '        }\n'
    '        serviceDestroyed = false\n'
    '        warmCurrentEngineAsync()\n'
    '    }\n\n'
    '    private fun warmCurrentEngineAsync() {\n'
    '        val model = TtsSettings.ttsModel(applicationContext)\n'
    '        if (!ModelManager.areTtsModelsReady(applicationContext, model)) {\n'
    '            Log.i(TAG, "TTS_WARM_SKIP model=$model reason=models-not-ready")\n'
    '            return\n'
    '        }\n'
    '        val rawBackend = TtsSettings.backend(applicationContext, model)\n'
    '        val backend = when {\n'
    '            !BuildConfig.ORT_XNNPACK_AVAILABLE && rawBackend == InferenceBackend.ONNX_XNNPACK -> InferenceBackend.CPU_ORT\n'
    '            model.isLiteRt && !rawBackend.isNativeCpu -> InferenceBackend.CPU_XNNPACK\n'
    '            else -> rawBackend\n'
    '        }\n'
    '        val voice = TtsSettings.voice(applicationContext)\n'
    '        val steps = TtsSettings.steps(applicationContext).coerceIn(1, 64)\n'
    '        val threads = TtsSettings.threads(applicationContext).coerceIn(1, 64)\n'
    '        warmThread = Thread({\n'
    '            val started = SystemClock.elapsedRealtimeNanos()\n'
    '            Log.i(TAG, "TTS_WARM_START model=$model backend=$backend threads=$threads")\n'
    '            try {\n'
    '                if (serviceDestroyed) return@Thread\n'
    '                val synth = getOrCreateSynthesizer(voice, steps, threads, backend, model)\n'
    '                if (serviceDestroyed) {\n'
    '                    synchronized(lock) {\n'
    '                        if (synthesizer === synth) {\n'
    '                            runCatching { synth.close() }\n'
    '                            synthesizer = null\n'
    '                        }\n'
    '                    }\n'
    '                    return@Thread\n'
    '                }\n'
    '                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0\n'
    '                Log.i(TAG, "TTS_WARM_READY model=$model backend=$backend sampleRate=${synth.sampleRate} elapsed_ms=${String.format(Locale.US, "%.1f", elapsedMs)}")\n'
    '            } catch (t: Throwable) {\n'
    '                if (!serviceDestroyed) Log.w(TAG, "TTS_WARM_FAIL model=$model backend=$backend", t)\n'
    '            }\n'
    '        }, "Supertonic-TTS-warm").apply {\n'
    '            isDaemon = true\n'
    '            start()\n'
    '        }\n'
    '    }\n',
)
replace_once(
    service,
    '    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {\n        val rawText = request.charSequenceText?.toString()?.trim().orEmpty()\n        if (rawText.isEmpty()) { callback.error(TextToSpeech.ERROR_INVALID_REQUEST); return }\n',
    '    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {\n'
    '        val requestId = requestSequence.incrementAndGet()\n'
    '        val requestEnterNs = SystemClock.elapsedRealtimeNanos()\n'
    '        val rawText = request.charSequenceText?.toString()?.trim().orEmpty()\n'
    '        Log.i(TAG, "TTS_REQ_ENTER id=$requestId len=${rawText.length} rate=${request.speechRate} pitch=${request.pitch}")\n'
    '        if (rawText.isEmpty()) { callback.error(TextToSpeech.ERROR_INVALID_REQUEST); return }\n',
)
replace_once(
    service,
    '            try {\n                val text = PronunciationRules.apply(applicationContext, rawText)\n',
    '            try {\n'
    '                val rulesStartNs = SystemClock.elapsedRealtimeNanos()\n'
    '                val text = PronunciationRules.apply(applicationContext, rawText)\n'
    '                val rulesMs = (SystemClock.elapsedRealtimeNanos() - rulesStartNs) / 1_000_000.0\n'
    '                Log.i(TAG, "TTS_RULES id=$requestId elapsed_ms=${String.format(Locale.US, "%.2f", rulesMs)} in_len=${rawText.length} out_len=${text.length}")\n',
)
replace_once(
    service,
    '                val configuredTrailingTrim = TtsSettings.trailingSilenceTrimMs(applicationContext)\n                val configuredOriginalFixedT = TtsSettings.originalFixedT(applicationContext)\n',
    '                val configuredTrailingTrim = TtsSettings.trailingSilenceTrimMs(applicationContext)\n'
    '                val configuredInternalSilence = TtsSettings.internalSilenceCompression(applicationContext)\n'
    '                val configuredInternalSilenceMax = TtsSettings.internalSilenceMaxPauseMs(applicationContext)\n'
    '                val configuredOriginalFixedT = TtsSettings.originalFixedT(applicationContext)\n',
)
replace_once(
    service,
    '                val requestRate = request.speechRate.coerceIn(10, 400) / 100f\n                val configuredSpeed = TtsSettings.speed(applicationContext).coerceIn(0.25f, 3.0f)\n                val effectiveSpeed = (configuredSpeed * requestRate).coerceIn(0.25f, 3.0f)\n',
    '                val requestRate = request.speechRate.coerceIn(10, 400) / 100f\n'
    '                val requestPitch = request.pitch.coerceIn(25, 400) / 100f\n'
    '                val configuredSpeed = TtsSettings.speed(applicationContext).coerceIn(0.25f, 3.0f)\n'
    '                val effectiveSpeed = (configuredSpeed * requestRate).coerceIn(0.25f, 3.0f)\n'
    '                val effectivePitch = requestPitch.coerceIn(0.25f, 4.0f)\n',
)
replace_once(
    service,
    '                var synth = getOrCreateSynthesizer(\n                    configuredVoice,\n                    configuredSteps,\n                    configuredThreads,\n                    configuredBackend,\n                    configuredTtsModel,\n                )\n',
    '                val engineWasCold = synchronized(lock) { synthesizer == null }\n'
    '                val engineStartNs = SystemClock.elapsedRealtimeNanos()\n'
    '                var synth = getOrCreateSynthesizer(\n'
    '                    configuredVoice,\n'
    '                    configuredSteps,\n'
    '                    configuredThreads,\n'
    '                    configuredBackend,\n'
    '                    configuredTtsModel,\n'
    '                )\n'
    '                Log.i(TAG, "TTS_ENGINE_READY id=$requestId cold=${if (engineWasCold) 1 else 0} elapsed_ms=${String.format(Locale.US, "%.1f", (SystemClock.elapsedRealtimeNanos() - engineStartNs) / 1_000_000.0)}")\n',
)
replace_once(
    service,
    '                Log.i(TAG, "SYNTH_APPLIED model=$configuredTtsModel backend=$configuredBackend voice=$configuredVoice speed=$effectiveSpeed steps=$configuredSteps chunk=$configuredChunkCap pregen=$configuredPregen queue=$configuredPregenQueue gap=$configuredGapMin-$configuredGapMax trailingTrim=$configuredTrailingTrim lang=$language requestVoice=${request.voiceName} requestRate=$requestRate rules=${PronunciationRules.count(applicationContext)}")\n',
    '                Log.i(TAG, "SYNTH_APPLIED model=$configuredTtsModel backend=$configuredBackend voice=$configuredVoice speed=$effectiveSpeed pitch=$effectivePitch steps=$configuredSteps chunk=$configuredChunkCap pregen=$configuredPregen queue=$configuredPregenQueue gap=$configuredGapMin-$configuredGapMax trailingTrim=$configuredTrailingTrim internalSilence=$configuredInternalSilence internalSilenceMax=$configuredInternalSilenceMax lang=$language requestVoice=${request.voiceName} requestRate=$requestRate rules=${PronunciationRules.count(applicationContext)}")\n',
)
replace_once(
    service,
    '                val speedStream = if (kotlin.math.abs(effectiveSpeed - 1.0f) >= 0.001f) {\n                    AudioSpeedProcessor.Stream(synth.sampleRate, effectiveSpeed)\n                } else null\n                var streamFinalSignaled = false\n',
    '                val silenceCompressor = if (configuredInternalSilence) {\n'
    '                    InternalSilenceCompressor(synth.sampleRate, configuredInternalSilenceMax)\n'
    '                } else null\n'
    '                val speedStream = if (kotlin.math.abs(effectiveSpeed - 1.0f) >= 0.001f || kotlin.math.abs(effectivePitch - 1.0f) >= 0.001f) {\n'
    '                    AudioSpeedProcessor.Stream(synth.sampleRate, effectiveSpeed, effectivePitch)\n'
    '                } else null\n'
    '                var streamFinalSignaled = false\n',
)
replace_once(
    service,
    '                            val mode = if (speedStream == null) "stream" else "stream-post-speed"\n                            Log.i(TAG, "SYNTH_TTFA ${String.format(java.util.Locale.US, "%.1f", ttfaMs)} ms mode=$mode speed=$effectiveSpeed chunk=$configuredChunkCap")\n',
    '                            val mode = buildString {\n'
    '                                append("stream")\n'
    '                                if (silenceCompressor != null) append("-silence")\n'
    '                                if (speedStream != null) append("-sonic")\n'
    '                            }\n'
    '                            Log.i(TAG, "SYNTH_TTFA ${String.format(java.util.Locale.US, "%.1f", ttfaMs)} ms mode=$mode speed=$effectiveSpeed pitch=$effectivePitch chunk=$configuredChunkCap")\n',
)
replace_once(
    service,
    '''                synth.synthesizeStreaming(text, language) { pcm, finalChunk ->
                    if (stopped) return@synthesizeStreaming
                    val output = if (speedStream == null) {
                        pcm
                    } else {
                        speedStream.process(pcm, final = false)
                    }
                    if (!emitToAndroid(output)) return@synthesizeStreaming

                    if (finalChunk && !streamFinalSignaled && !stopped) {
                        // Sonic keeps a small internal tail. Flush it only once at the utterance
                        // boundary so rate conversion is continuous across every native chunk.
                        if (speedStream != null) {
                            if (!emitToAndroid(speedStream.process(ByteArray(0), final = true))) {
                                return@synthesizeStreaming
                            }
                        }
                        streamFinalSignaled = true
                        signalDone()
                    }
                }
                val speedProcessMs = speedStream?.processingMs ?: 0.0
                Log.i(TAG, "SYNTH_PROFILE ${synth.lastProfile()}; speed_process_ms=$speedProcessMs; speed_stream=${if (speedStream == null) 0 else 1}")
''',
    '''                synth.synthesizeStreaming(text, language) { pcm, finalChunk ->
                    if (stopped) return@synthesizeStreaming
                    val nativePost = silenceCompressor?.process(pcm, final = false) ?: pcm
                    val output = speedStream?.process(nativePost, final = false) ?: nativePost
                    if (!emitToAndroid(output)) return@synthesizeStreaming

                    if (finalChunk && !streamFinalSignaled && !stopped) {
                        // Flush the silence detector first. Pending silence at the utterance
                        // boundary is trailing silence, so it is preserved rather than capped.
                        val silenceTail = silenceCompressor?.process(ByteArray(0), final = true) ?: ByteArray(0)
                        if (silenceTail.isNotEmpty()) {
                            val tailOutput = speedStream?.process(silenceTail, final = false) ?: silenceTail
                            if (!emitToAndroid(tailOutput)) return@synthesizeStreaming
                        }
                        // Sonic keeps a small internal tail. Flush it only once at the utterance
                        // boundary so rate/pitch conversion stays continuous across all chunks.
                        if (speedStream != null) {
                            if (!emitToAndroid(speedStream.process(ByteArray(0), final = true))) {
                                return@synthesizeStreaming
                            }
                        }
                        streamFinalSignaled = true
                        signalDone()
                    }
                }
                val speedProcessMs = speedStream?.processingMs ?: 0.0
                val silenceRuns = silenceCompressor?.compressedRuns ?: 0
                val silenceRemovedMs = silenceCompressor?.removedMs ?: 0.0
                Log.i(TAG, "SYNTH_PROFILE ${synth.lastProfile()}; speed_process_ms=$speedProcessMs; speed_stream=${if (speedStream == null) 0 else 1}; pitch=$effectivePitch; internal_silence=${if (silenceCompressor == null) 0 else 1}; internal_silence_runs=$silenceRuns; internal_silence_removed_ms=${String.format(Locale.US, "%.1f", silenceRemovedMs)}")
''',
)
replace_once(
    service,
    '            } finally {\n                releaseSynthesisWakeLockAfterLinger()\n            }\n',
    '            } finally {\n'
    '                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - requestEnterNs) / 1_000_000.0\n'
    '                Log.i(TAG, "TTS_REQ_EXIT id=$requestId elapsed_ms=${String.format(Locale.US, "%.1f", elapsedMs)} stopped=${if (stopped) 1 else 0}")\n'
    '                releaseSynthesisWakeLockAfterLinger()\n'
    '            }\n',
)
replace_once(
    service,
    '    override fun onDestroy() {\n        wakeLockHandler.removeCallbacks(wakeLockRelease)\n',
    '    override fun onDestroy() {\n'
    '        serviceDestroyed = true\n'
    '        warmThread?.interrupt()\n'
    '        warmThread = null\n'
    '        wakeLockHandler.removeCallbacks(wakeLockRelease)\n',
)

# ---------------------------------------------------------------------------
# MainActivity: retain the existing compact 2-column card UI and append exactly
# one matching row under Silence Trim. Apply the same PCM pipeline to previews.
# ---------------------------------------------------------------------------
main = "app/src/main/kotlin/com/supertonic/tts/MainActivity.kt"
replace_once(
    main,
    'import audio.soniqo.speech.audio.AudioSpeedProcessor\n',
    'import audio.soniqo.speech.audio.AudioSpeedProcessor\nimport audio.soniqo.speech.audio.InternalSilenceCompressor\n',
)
replace_once(
    main,
    '    private lateinit var trailingTrimInput: EditText\n    private lateinit var languageSpinner: Spinner\n',
    '    private lateinit var trailingTrimInput: EditText\n'
    '    private lateinit var internalSilenceSpinner: Spinner\n'
    '    private lateinit var internalSilenceMaxInput: EditText\n'
    '    private lateinit var languageSpinner: Spinner\n',
)
replace_once(
    main,
    '''        addCard(root, weightedRow(
            chunkGapCard,
            compactField("Silence Trim (ms)", trailingTrimInput),
        ))

        // NPU-specific controls come after the three common two-column rows.
''',
    '''        addCard(root, weightedRow(
            chunkGapCard,
            compactField("Silence Trim (ms)", trailingTrimInput),
        ))

        internalSilenceSpinner = spinner(listOf("OFF", "ON"))
        internalSilenceMaxInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "100–500"
            background = rounded(Color.rgb(252, 251, 253), 10f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        addCard(root, weightedRow(
            compactField("Internal Silence", internalSilenceSpinner),
            compactField("Max Pause @1x (ms)", internalSilenceMaxInput),
        ))

        // NPU-specific controls come after the common two-column rows.
''',
)
replace_once(
    main,
    '        trailingTrimInput.setText(TtsSettings.trailingSilenceTrimMs(this).toString())\n        originalShapeSpinner.setSelection',
    '        trailingTrimInput.setText(TtsSettings.trailingSilenceTrimMs(this).toString())\n'
    '        internalSilenceSpinner.setSelection(if (TtsSettings.internalSilenceCompression(this)) 1 else 0)\n'
    '        internalSilenceMaxInput.setText(TtsSettings.internalSilenceMaxPauseMs(this).toString())\n'
    '        syncInternalSilenceUi()\n'
    '        originalShapeSpinner.setSelection',
)
replace_once(
    main,
    '        trailingTrimInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitStreamingControls() }\n\n        preGenerationSpinner.onItemSelectedListener',
    '        trailingTrimInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitStreamingControls() }\n\n'
    '        val commitInternalSilence = {\n'
    '            val enabled = internalSilenceSpinner.selectedItemPosition == 1\n'
    '            val maxPause = currentInternalSilenceMax()\n'
    '            internalSilenceMaxInput.setText(maxPause.toString())\n'
    '            TtsSettings.setInternalSilenceControls(this@MainActivity, enabled, maxPause)\n'
    '            syncInternalSilenceUi()\n'
    '        }\n'
    '        internalSilenceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {\n'
    '            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit\n'
    '            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {\n'
    '                commitInternalSilence()\n'
    '            }\n'
    '        }\n'
    '        internalSilenceMaxInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitInternalSilence() }\n\n'
    '        preGenerationSpinner.onItemSelectedListener',
)
replace_once(
    main,
    '    private fun currentTrailingTrim(): Int = trailingTrimInput.text?.toString()?.toIntOrNull()?.coerceIn(TtsSettings.MIN_TRAILING_TRIM_MS, TtsSettings.MAX_TRAILING_TRIM_MS) ?: TtsSettings.DEFAULT_TRAILING_TRIM_MS\n    private fun currentSpeed(): Float',
    '    private fun currentTrailingTrim(): Int = trailingTrimInput.text?.toString()?.toIntOrNull()?.coerceIn(TtsSettings.MIN_TRAILING_TRIM_MS, TtsSettings.MAX_TRAILING_TRIM_MS) ?: TtsSettings.DEFAULT_TRAILING_TRIM_MS\n'
    '    private fun currentInternalSilenceMax(): Int = internalSilenceMaxInput.text?.toString()?.toIntOrNull()\n'
    '        ?.coerceIn(TtsSettings.MIN_INTERNAL_SILENCE_MAX_MS, TtsSettings.MAX_INTERNAL_SILENCE_MAX_MS)\n'
    '        ?: TtsSettings.DEFAULT_INTERNAL_SILENCE_MAX_MS\n'
    '    private fun syncInternalSilenceUi() {\n'
    '        val enabled = internalSilenceSpinner.selectedItemPosition == 1\n'
    '        internalSilenceMaxInput.isEnabled = enabled\n'
    '        internalSilenceMaxInput.alpha = if (enabled) 1.0f else 0.45f\n'
    '    }\n'
    '    private fun currentSpeed(): Float',
)
replace_once(
    main,
    '        TtsSettings.setStreamingControls(this, 1, currentChunkGapMin(), currentChunkGapMax(), currentTrailingTrim())\n        TtsSettings.setTestLanguage(this, currentLanguage())\n',
    '        TtsSettings.setStreamingControls(this, 1, currentChunkGapMin(), currentChunkGapMax(), currentTrailingTrim())\n'
    '        TtsSettings.setInternalSilenceControls(this, internalSilenceSpinner.selectedItemPosition == 1, currentInternalSilenceMax())\n'
    '        TtsSettings.setTestLanguage(this, currentLanguage())\n',
)
replace_once(
    main,
    '        val speedStream = if (kotlin.math.abs(speed - 1.0f) >= 0.001f) {\n            AudioSpeedProcessor.Stream(sampleRate, speed)\n        } else null\n        val chunks = ArrayList<ByteArray>()\n',
    '        val silenceCompressor = if (TtsSettings.internalSilenceCompression(this)) {\n'
    '            InternalSilenceCompressor(sampleRate, TtsSettings.internalSilenceMaxPauseMs(this))\n'
    '        } else null\n'
    '        val speedStream = if (kotlin.math.abs(speed - 1.0f) >= 0.001f) {\n'
    '            AudioSpeedProcessor.Stream(sampleRate, speed)\n'
    '        } else null\n'
    '        val chunks = ArrayList<ByteArray>()\n',
)
replace_once(
    main,
    '''            synth.synthesizeStreaming(text, language) { pcm, final ->
                nativeBytes += pcm.size.toLong()
                val output = if (speedStream == null) pcm else speedStream.process(pcm, final = false)
                appendOutput(output)
                if (final && !finalSeen) {
                    if (speedStream != null) appendOutput(speedStream.process(ByteArray(0), final = true))
                    finalSeen = true
                }
            }
''',
    '''            synth.synthesizeStreaming(text, language) { pcm, final ->
                nativeBytes += pcm.size.toLong()
                val nativePost = silenceCompressor?.process(pcm, final = false) ?: pcm
                val output = speedStream?.process(nativePost, final = false) ?: nativePost
                appendOutput(output)
                if (final && !finalSeen) {
                    val silenceTail = silenceCompressor?.process(ByteArray(0), final = true) ?: ByteArray(0)
                    if (silenceTail.isNotEmpty()) {
                        appendOutput(speedStream?.process(silenceTail, final = false) ?: silenceTail)
                    }
                    if (speedStream != null) appendOutput(speedStream.process(ByteArray(0), final = true))
                    finalSeen = true
                }
            }
''',
)
replace_once(
    main,
    '''                        val whole = synth.synthesize(text, lang)
                        UiSynthesisOutput(
                            sampleRate = whole.sampleRate,
                            pcm16 = whole.pcm16,
                            profile = whole.profile,
                            nativePcmBytes = whole.pcm16.size.toLong(),
''',
    '''                        val whole = synth.synthesize(text, lang)
                        val postSilencePcm = if (TtsSettings.internalSilenceCompression(this@MainActivity)) {
                            InternalSilenceCompressor(
                                whole.sampleRate,
                                TtsSettings.internalSilenceMaxPauseMs(this@MainActivity),
                            ).process(whole.pcm16, final = true)
                        } else whole.pcm16
                        UiSynthesisOutput(
                            sampleRate = whole.sampleRate,
                            pcm16 = postSilencePcm,
                            profile = whole.profile,
                            nativePcmBytes = whole.pcm16.size.toLong(),
''',
)
replace_once(
    main,
    '        sb.append("Speed processing        ").append(String.format(Locale.US, "%.1f ms", speedProcessMs)).append(\'\\n\')\n',
    '        sb.append("Speed processing        ").append(String.format(Locale.US, "%.1f ms", speedProcessMs)).append(\'\\n\')\n'
    '        sb.append("Internal silence        ").append(\n'
    '            if (TtsSettings.internalSilenceCompression(this)) "ON · ${TtsSettings.internalSilenceMaxPauseMs(this)} ms @1x" else "OFF"\n'
    '        ).append(\'\\n\')\n',
)

# Version bump for the modified test build.
gradle = "app/build.gradle.kts"
replace_once(gradle, '        versionCode = 46\n', '        versionCode = 47\n')
replace_once(gradle, '        versionName = "0.1.38"\n', '        versionName = "0.1.39"\n')

print("TTS pause/pitch/warm-path improvements applied successfully")
