package audio.soniqo.speech.service

import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.BuildConfig
import audio.soniqo.speech.InferenceBackend
import audio.soniqo.speech.SpeechSynthesizer
import audio.soniqo.speech.SpeechSynthesizerConfig
import audio.soniqo.speech.TtsSettings
import audio.soniqo.speech.TtsModel
import audio.soniqo.speech.rules.PronunciationRules
import audio.soniqo.speech.audio.AudioSpeedProcessor
import audio.soniqo.speech.audio.InternalSilenceCompressor
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Android system TextToSpeechService backed by Soniqo Supertonic-3 LiteRT. */
class SpeechTextToSpeechService : TextToSpeechService() {
    private val lock = Any()
    private val synthesisLock = Any()
    private val wakeLockHandler = Handler(Looper.getMainLooper())
    @Volatile private var synthesisWakeLock: PowerManager.WakeLock? = null
    private val wakeLockRelease = Runnable { releaseSynthesisWakeLock() }
    private val wakeLockLingerMs = 5000L
    @Volatile private var synthesizer: SpeechSynthesizer? = null
    @Volatile private var stopped = false
    @Volatile private var selectedVoice = "F1"
    @Volatile private var loadedLang3 = "eng"
    @Volatile private var loadedCountry3 = "USA"
    @Volatile private var loadedThreads = 0
    @Volatile private var loadedBackend = InferenceBackend.CPU_XNNPACK
    @Volatile private var loadedTtsModel = TtsModel.SUPERTONIC
    @Volatile private var loadedOriginalFixedT = 0
    @Volatile private var loadedOriginalFixedL = 0
    @Volatile private var loadedDeepProfiler = false
    @Volatile private var serviceDestroyed = false
    @Volatile private var warmThread: Thread? = null
    private val requestSequence = AtomicLong(0L)

    override fun onCreate() {
        super.onCreate()
        ModelManager.migrateAndSyncCustomVoices(applicationContext)
        ModelManager.cleanupRetiredModels(applicationContext)
        selectedVoice = TtsSettings.voice(applicationContext)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        synthesisWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${packageName}:TTS-Synthesis"
        ).apply {
            setReferenceCounted(false)
        }
        serviceDestroyed = false
        warmCurrentEngineAsync()
    }

    private fun warmCurrentEngineAsync() {
        val model = TtsSettings.ttsModel(applicationContext)
        if (!ModelManager.areTtsModelsReady(applicationContext, model)) {
            Log.i(TAG, "TTS_WARM_SKIP model=$model reason=models-not-ready")
            return
        }
        val rawBackend = TtsSettings.backend(applicationContext, model)
        val backend = when {
            !BuildConfig.ORT_XNNPACK_AVAILABLE && rawBackend == InferenceBackend.ONNX_XNNPACK -> InferenceBackend.CPU_ORT
            model.isLiteRt && !rawBackend.isNativeCpu -> InferenceBackend.CPU_XNNPACK
            else -> rawBackend
        }
        val voice = TtsSettings.voice(applicationContext)
        val steps = TtsSettings.steps(applicationContext).coerceIn(1, 64)
        val threads = TtsSettings.threads(applicationContext).coerceIn(1, 64)
        warmThread = Thread({
            val started = SystemClock.elapsedRealtimeNanos()
            Log.i(TAG, "TTS_WARM_START model=$model backend=$backend threads=$threads")
            try {
                if (serviceDestroyed) return@Thread
                val synth = getOrCreateSynthesizer(voice, steps, threads, backend, model)
                if (serviceDestroyed) {
                    synchronized(lock) {
                        if (synthesizer === synth) {
                            runCatching { synth.close() }
                            synthesizer = null
                        }
                    }
                    return@Thread
                }
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                Log.i(TAG, "TTS_WARM_READY model=$model backend=$backend sampleRate=${synth.sampleRate} elapsed_ms=${String.format(Locale.US, "%.1f", elapsedMs)}")
            } catch (t: Throwable) {
                if (!serviceDestroyed) Log.w(TAG, "TTS_WARM_FAIL model=$model backend=$backend", t)
            }
        }, "Supertonic-TTS-warm").apply {
            isDaemon = true
            start()
        }
    }

    private fun acquireSynthesisWakeLock() {
        wakeLockHandler.removeCallbacks(wakeLockRelease)
        val wl = synthesisWakeLock ?: return
        if (!wl.isHeld) {
            wl.acquire()
            Log.d(TAG, "TTS_WAKELOCK acquired")
        }
    }

    private fun releaseSynthesisWakeLock() {
        val wl = synthesisWakeLock ?: return
        if (wl.isHeld) {
            wl.release()
            Log.d(TAG, "TTS_WAKELOCK released")
        }
    }

    private fun releaseSynthesisWakeLockAfterLinger() {
        wakeLockHandler.removeCallbacks(wakeLockRelease)
        wakeLockHandler.postDelayed(wakeLockRelease, wakeLockLingerMs)
    }

    override fun onGetLanguage(): Array<String> = arrayOf(loadedLang3, loadedCountry3, "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = languageAvailability(lang)

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val normalized = normalizeLanguage(lang)
        if (normalized !in LANGS) return TextToSpeech.LANG_NOT_SUPPORTED
        loadedLang3 = lang?.takeIf { it.length == 3 } ?: toIso3(normalized)
        loadedCountry3 = country?.takeIf { it.isNotBlank() } ?: defaultCountry(normalized)
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetVoices(): MutableList<Voice> {
        val result = mutableListOf<Voice>()
        for (id in builtinVoiceIds()) {
            // networkConnectionRequired=false is the API 21+ way to expose an
            // offline voice. KEY_FEATURE_EMBEDDED_SYNTHESIS is deprecated.
            result += Voice(
                voiceNameForId(id), Locale.ROOT, Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL, false, emptySet(),
            )
        }
        for (id in customVoiceIds()) {
            result += Voice(
                voiceNameForId(id), Locale.ROOT, Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL, false, emptySet(),
            )
        }
        return result
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        if (languageAvailability(lang) == TextToSpeech.LANG_NOT_SUPPORTED) return null
        return voiceNameForId(TtsSettings.voice(applicationContext))
    }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceIdFromName(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int {
        val id = voiceIdFromName(voiceName) ?: return TextToSpeech.ERROR
        synchronized(lock) {
            selectedVoice = id
            synthesizer?.close()
            synthesizer = null
        }
        TtsSettings.save(applicationContext, selectedVoice, TtsSettings.speed(applicationContext), TtsSettings.steps(applicationContext), TtsSettings.threads(applicationContext))
        return TextToSpeech.SUCCESS
    }

    override fun onStop() {
        stopped = true
        synthesizer?.stop()
        wakeLockHandler.removeCallbacks(wakeLockRelease)
        releaseSynthesisWakeLock()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val requestId = requestSequence.incrementAndGet()
        val requestEnterNs = SystemClock.elapsedRealtimeNanos()
        val rawText = request.charSequenceText?.toString()?.trim().orEmpty()
        Log.i(TAG, "TTS_REQ_ENTER id=$requestId len=${rawText.length} rate=${request.speechRate} pitch=${request.pitch}")
        if (rawText.isEmpty()) { callback.error(TextToSpeech.ERROR_INVALID_REQUEST); return }
        stopped = false
        acquireSynthesisWakeLock()
        synchronized(synthesisLock) {
            var terminalSignaled = false
            fun signalError() {
                if (!terminalSignaled) {
                    terminalSignaled = true
                    callback.error(TextToSpeech.ERROR_SYNTHESIS)
                }
            }
            fun signalDone() {
                if (!terminalSignaled) {
                    terminalSignaled = true
                    callback.done()
                }
            }
            try {
                val rulesStartNs = SystemClock.elapsedRealtimeNanos()
                val text = PronunciationRules.apply(applicationContext, rawText)
                val rulesMs = (SystemClock.elapsedRealtimeNanos() - rulesStartNs) / 1_000_000.0
                Log.i(TAG, "TTS_RULES id=$requestId elapsed_ms=${String.format(Locale.US, "%.2f", rulesMs)} in_len=${rawText.length} out_len=${text.length}")
                val languageRequested = normalizeLanguage(request.language)
                if (languageRequested !in LANGS) { callback.error(TextToSpeech.ERROR_INVALID_REQUEST); return }
                val language = when {
                    languageRequested == "na" -> "na"
                    PronunciationRules.isMixedScript(text) && TtsSettings.allowNa(applicationContext) -> "na"
                    else -> languageRequested
                }

                // Stored engine preference is authoritative for this custom TTS engine.
                val configuredVoice = TtsSettings.voice(applicationContext)
                selectedVoice = configuredVoice
                val configuredSteps = TtsSettings.steps(applicationContext).coerceIn(1, 64)
                val configuredThreads = TtsSettings.threads(applicationContext).coerceIn(1, 64)
                val configuredTtsModel = TtsSettings.ttsModel(applicationContext)
                val storedBackendRaw = TtsSettings.backend(applicationContext, configuredTtsModel)
                val storedBackend = if (
                    !BuildConfig.ORT_XNNPACK_AVAILABLE &&
                    storedBackendRaw == InferenceBackend.ONNX_XNNPACK
                ) {
                    Log.w(TAG, "ORT_RUNTIME_MIGRATION: ONNX_XNNPACK -> CPU for ${BuildConfig.ORT_RUNTIME_VARIANT}")
                    TtsSettings.setBackend(applicationContext, configuredTtsModel, InferenceBackend.CPU_ORT)
                    InferenceBackend.CPU_ORT
                } else {
                    storedBackendRaw
                }
                val configuredBackend = if (
                    configuredTtsModel.isLiteRt &&
                    !storedBackend.isNativeCpu
                ) {
                    // Both LiteRT model identities stay native CPU/XNNPACK-only.
                    // ONNX-only CPU XNN and Qualcomm NPU preferences must never
                    // leak into either LiteRT engine.
                    Log.w(TAG, "LITERT_BACKEND_MIGRATION: ${storedBackend.name} -> CPU")
                    TtsSettings.setBackend(applicationContext, configuredTtsModel, InferenceBackend.CPU_XNNPACK)
                    InferenceBackend.CPU_XNNPACK
                } else {
                    storedBackend
                }
                val configuredChunkCap = TtsSettings.chunkCap(applicationContext)
                val configuredPregen = TtsSettings.preGeneration(applicationContext) &&
                    configuredBackend != InferenceBackend.QUALCOMM_NPU
                val configuredPregenQueue = 1
                val configuredGapMin = TtsSettings.chunkGapMinMs(applicationContext)
                val configuredGapMax = TtsSettings.chunkGapMaxMs(applicationContext)
                val configuredTrailingTrim = TtsSettings.trailingSilenceTrimMs(applicationContext)
                val configuredInternalSilence = TtsSettings.internalSilenceCompression(applicationContext)
                val configuredInternalSilenceMax = TtsSettings.internalSilenceMaxPauseMs(applicationContext)
                val configuredOriginalFixedT = TtsSettings.originalFixedT(applicationContext)
                val configuredOriginalFixedL = TtsSettings.originalFixedL(applicationContext)
                val configuredDeepProfiler = TtsSettings.deepProfiler(applicationContext)
                val requestRate = request.speechRate.coerceIn(10, 400) / 100f
                val requestPitch = request.pitch.coerceIn(25, 400) / 100f
                val configuredSpeed = TtsSettings.speed(applicationContext).coerceIn(0.25f, 3.0f)
                val effectiveSpeed = (configuredSpeed * requestRate).coerceIn(0.25f, 3.0f)
                val effectivePitch = requestPitch.coerceIn(0.25f, 4.0f)

                if (
                    loadedThreads != configuredThreads ||
                    loadedBackend != configuredBackend ||
                    loadedTtsModel != configuredTtsModel ||
                    loadedOriginalFixedT != configuredOriginalFixedT ||
                    loadedOriginalFixedL != configuredOriginalFixedL ||
                    loadedDeepProfiler != configuredDeepProfiler
                ) {
                    synchronized(lock) { synthesizer?.close(); synthesizer = null }
                }
                val engineWasCold = synchronized(lock) { synthesizer == null }
                val engineStartNs = SystemClock.elapsedRealtimeNanos()
                var synth = getOrCreateSynthesizer(
                    configuredVoice,
                    configuredSteps,
                    configuredThreads,
                    configuredBackend,
                    configuredTtsModel,
                )
                Log.i(TAG, "TTS_ENGINE_READY id=$requestId cold=${if (engineWasCold) 1 else 0} elapsed_ms=${String.format(Locale.US, "%.1f", (SystemClock.elapsedRealtimeNanos() - engineStartNs) / 1_000_000.0)}")
                try {
                    synth.setVoice(configuredVoice)
                } catch (_: Throwable) {
                    // A custom voice may have been imported after this service instance created
                    // its native voice table. Recreate once so the new JSON is loaded.
                    synchronized(lock) { synthesizer?.close(); synthesizer = null }
                    synth = getOrCreateSynthesizer(
                        configuredVoice,
                        configuredSteps,
                        configuredThreads,
                        configuredBackend,
                        configuredTtsModel,
                    )
                    synth.setVoice(configuredVoice)
                }
                synth.setSpeed(1.0f)
                synth.setTotalSteps(configuredSteps)
                synth.setChunkCap(configuredChunkCap)
                synth.setPreGeneration(configuredPregen)
                synth.setPreGenerationQueue(configuredPregenQueue)
                synth.setChunkGap(configuredGapMin, configuredGapMax)
                synth.setTrailingSilenceTrimMs(configuredTrailingTrim)

                Log.i(TAG, "SYNTH_APPLIED model=$configuredTtsModel backend=$configuredBackend voice=$configuredVoice speed=$effectiveSpeed pitch=$effectivePitch steps=$configuredSteps chunk=$configuredChunkCap pregen=$configuredPregen queue=$configuredPregenQueue gap=$configuredGapMin-$configuredGapMax trailingTrim=$configuredTrailingTrim internalSilence=$configuredInternalSilence internalSilenceMax=$configuredInternalSilenceMax lang=$language requestVoice=${request.voiceName} requestRate=$requestRate rules=${PronunciationRules.count(applicationContext)}")

                // The model ALWAYS synthesizes at 1.0x.  Direct high-speed model inference can
                // shorten duration prediction enough to drop syllables/words on Supertonic.
                // Speech-rate adjustment therefore remains a post-process.  REV33 keeps one
                // stateful Sonic stream for the entire utterance so non-1.0x requests can still
                // use native streaming/pre-generation without resetting Sonic at chunk seams.
                val requestStartNs = SystemClock.elapsedRealtimeNanos()
                var firstAudioNs = 0L
                if (callback.start(synth.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                    signalError()
                    return
                }
                val silenceCompressor = if (configuredInternalSilence) {
                    InternalSilenceCompressor(synth.sampleRate, configuredInternalSilenceMax)
                } else null
                val speedStream = if (kotlin.math.abs(effectiveSpeed - 1.0f) >= 0.001f || kotlin.math.abs(effectivePitch - 1.0f) >= 0.001f) {
                    AudioSpeedProcessor.Stream(synth.sampleRate, effectiveSpeed, effectivePitch)
                } else null
                var streamFinalSignaled = false

                fun emitToAndroid(bytes: ByteArray): Boolean {
                    if (bytes.isEmpty() || stopped) return !stopped
                    var offset = 0
                    while (offset < bytes.size && !stopped) {
                        val count = minOf(callback.maxBufferSize.coerceAtLeast(1024), bytes.size - offset)
                        if (callback.audioAvailable(bytes, offset, count) != TextToSpeech.SUCCESS) {
                            signalError()
                            synth.stop()
                            return false
                        }
                        if (firstAudioNs == 0L) {
                            firstAudioNs = SystemClock.elapsedRealtimeNanos()
                            val ttfaMs = (firstAudioNs - requestStartNs) / 1_000_000.0
                            val mode = buildString {
                                append("stream")
                                if (silenceCompressor != null) append("-silence")
                                if (speedStream != null) append("-sonic")
                            }
                            Log.i(TAG, "SYNTH_TTFA ${String.format(java.util.Locale.US, "%.1f", ttfaMs)} ms mode=$mode speed=$effectiveSpeed pitch=$effectivePitch chunk=$configuredChunkCap")
                        }
                        offset += count
                    }
                    return !stopped
                }

                synth.synthesizeStreaming(text, language) { pcm, finalChunk ->
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
                if (streamFinalSignaled) {
                    releaseSynthesisWakeLockAfterLinger()
                    return
                }
                if (stopped) signalError() else signalDone()
            } catch (t: Throwable) {
                Log.e(TAG, "TTS synthesis failed", t)
                signalError()
            } finally {
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - requestEnterNs) / 1_000_000.0
                Log.i(TAG, "TTS_REQ_EXIT id=$requestId elapsed_ms=${String.format(Locale.US, "%.1f", elapsedMs)} stopped=${if (stopped) 1 else 0}")
                releaseSynthesisWakeLockAfterLinger()
            }
        }
    }

    override fun onDestroy() {
        serviceDestroyed = true
        warmThread?.interrupt()
        warmThread = null
        wakeLockHandler.removeCallbacks(wakeLockRelease)
        releaseSynthesisWakeLock()
        synchronized(lock) { synthesizer?.close(); synthesizer = null }
        super.onDestroy()
    }

    private fun getOrCreateSynthesizer(
        voiceId: String,
        totalSteps: Int,
        numThreads: Int,
        backend: InferenceBackend,
        ttsModel: TtsModel,
    ): SpeechSynthesizer {
        synchronized(lock) { synthesizer?.let { return it } }
        val modelDir = runBlocking {
            ModelManager.ensureTtsModels(applicationContext, ttsModel)
        }
        return synchronized(lock) {
            synthesizer ?: SpeechSynthesizer(
                SpeechSynthesizerConfig(
                    modelDir = modelDir,
                    useNnapi = false,
                    backend = backend,
                    ttsModel = ttsModel,
                    voiceId = voiceId,
                    speed = 1.0f,
                    totalSteps = totalSteps,
                    numThreads = numThreads,
                    chunkCap = TtsSettings.chunkCap(applicationContext),
                    preGenerationQueue = TtsSettings.preGenerationQueue(applicationContext),
                    chunkGapMinMs = TtsSettings.chunkGapMinMs(applicationContext),
                    chunkGapMaxMs = TtsSettings.chunkGapMaxMs(applicationContext),
                    trailingSilenceTrimMs = TtsSettings.trailingSilenceTrimMs(applicationContext),
                    originalFixedTextT = TtsSettings.originalFixedT(applicationContext),
                    originalFixedLatentL = TtsSettings.originalFixedL(applicationContext),
                    nativeLibraryDir = applicationInfo.nativeLibraryDir,
                    acceleratorCacheDir = File(cacheDir, "accelerator_cache").apply { mkdirs() }.absolutePath,
                    enableDeepProfiler = TtsSettings.deepProfiler(applicationContext),
                )
            ).also {
                loadedThreads = numThreads
                loadedBackend = backend
                loadedTtsModel = ttsModel
                loadedOriginalFixedT = TtsSettings.originalFixedT(applicationContext)
                loadedOriginalFixedL = TtsSettings.originalFixedL(applicationContext)
                loadedDeepProfiler = TtsSettings.deepProfiler(applicationContext)
                synthesizer = it
            }
        }
    }

    private fun builtinVoiceIds(): List<String> = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")

    private fun customVoiceIds(): List<String> = runCatching {
        ModelManager.customVoiceFiles(applicationContext)
            .map { it.nameWithoutExtension }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }.getOrDefault(emptyList())

    private fun voiceNameForId(id: String): String = when (id.uppercase(Locale.ROOT)) {
        "F1", "F2", "F3", "F4", "F5" -> "supertonic-f${id.substring(1)}"
        "M1", "M2", "M3", "M4", "M5" -> "supertonic-m${id.substring(1)}"
        else -> "supertonic-custom-${id.removePrefix("custom_")}"
    }

    private fun voiceIdFromName(name: String?): String? {
        if (name == null) return null
        builtinVoiceIds().firstOrNull { voiceNameForId(it) == name }?.let { return it }
        if (name.startsWith("supertonic-custom-")) {
            val id = "custom_" + name.removePrefix("supertonic-custom-")
            return if (id in customVoiceIds()) id else null
        }
        return null
    }

    companion object {
        private const val TAG = "SupertonicTTS"
        private val LANGS = setOf(
            "na", "en", "ko", "ja", "zh", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv",
            "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi"
        )
        fun normalizeLanguage(lang: String?): String {
            val l = lang?.lowercase(Locale.ROOT).orEmpty()
            return when (l) {
                "eng" -> "en"; "kor" -> "ko"; "jpn" -> "ja"; "ara" -> "ar"; "bul" -> "bg"; "ces" -> "cs"; "dan" -> "da"; "deu" -> "de";
                "ell" -> "el"; "spa" -> "es"; "est" -> "et"; "fin" -> "fi"; "fra" -> "fr"; "hin" -> "hi"; "hrv" -> "hr"; "hun" -> "hu";
                "ind" -> "id"; "ita" -> "it"; "lit" -> "lt"; "lav" -> "lv"; "nld" -> "nl"; "pol" -> "pl"; "por" -> "pt"; "ron" -> "ro";
                "rus" -> "ru"; "slk" -> "sk"; "slv" -> "sl"; "swe" -> "sv"; "tur" -> "tr"; "ukr" -> "uk"; "vie" -> "vi"; "zho", "chi", "cmn" -> "zh";
                "" -> "en"; else -> l.substringBefore('-').ifBlank { "en" }
            }
        }
        private fun languageAvailability(lang: String?): Int = if (normalizeLanguage(lang) in LANGS) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
        private fun toIso3(lang: String): String = when (lang) {
            "na" -> "eng"; "ko" -> "kor"; "ja" -> "jpn"; "zh" -> "zho"; "de" -> "deu"; "fr" -> "fra"; "es" -> "spa"; "en" -> "eng"
            else -> Locale.forLanguageTag(lang).isO3Language.ifBlank { lang }
        }
        private fun defaultCountry(lang: String): String = when (lang) {
            "ko" -> "KOR"; "ja" -> "JPN"; "zh" -> "CHN"; "en", "na" -> "USA"; else -> ""
        }
    }
}
