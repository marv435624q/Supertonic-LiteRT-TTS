package audio.soniqo.speech

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File

internal object NativeBridge {
    @Volatile
    private var loaded = false

    /**
     * Load the exact LiteRT runtime packaged with the app before loading the JNI
     * bridge that depends on it.
     *
     * Do not perform this in an object initializer. A failed object initializer
     * poisons the class for the rest of the process, so every later attempt is
     * reduced to an unhelpful `NoClassDefFoundError: NativeBridge` and the first
     * linker error is lost.
     */
    fun ensureLoaded(nativeLibraryDir: String) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return

            try {
                loadPackagedLibrary(nativeLibraryDir, "LiteRt")
                loadPackagedLibrary(nativeLibraryDir, "speech_android")
                loaded = true
            } catch (failure: Throwable) {
                val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                val pageSize = runCatching {
                    Os.sysconf(OsConstants._SC_PAGESIZE)
                }.getOrDefault(-1L)
                val files = listOf("libLiteRt.so", "libspeech_android.so", "libc++_shared.so")
                    .joinToString(",") { name ->
                        val file = nativeLibraryDir.takeIf { it.isNotBlank() }
                            ?.let { File(it, name) }
                        "$name=${if (file?.isFile == true) "present" else "missing"}"
                    }
                val linkerMessage = deepestMessage(failure)
                throw IllegalStateException(
                    "LiteRT native runtime load failed " +
                        "(abi=$abi,pageSize=$pageSize,$files): $linkerMessage",
                    failure,
                )
            }
        }
    }

    private fun loadPackagedLibrary(nativeLibraryDir: String, shortName: String) {
        val packaged = nativeLibraryDir.takeIf { it.isNotBlank() }
            ?.let { File(it, System.mapLibraryName(shortName)) }
        if (packaged?.isFile == true) {
            // Legacy native packaging extracts .so files into nativeLibraryDir.
            // Loading the absolute file prevents an older vendor/system library
            // with the same SONAME from winning resolution on customized ROMs.
            System.load(packaged.absolutePath)
        } else {
            System.loadLibrary(shortName)
        }
    }

    private fun deepestMessage(failure: Throwable): String {
        var current: Throwable? = failure
        var result = failure.message?.takeIf { it.isNotBlank() }
            ?: failure.javaClass.simpleName
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message?.takeIf { it.isNotBlank() }
            if (message != null) result = message
            current = current.cause
        }
        return result.replace('\n', ' ').trim()
    }

    external fun nativeCreateSynthesizer(
        modelDir: String,
        useNnapi: Boolean,
        backend: Int,
        ttsModel: Int,
        voiceId: String,
        totalSteps: Int,
        speed: Float,
        numThreads: Int,
        chunkCap: Int,
        nativeLibraryDir: String,
        acceleratorCacheDir: String,
        enableDeepProfiler: Boolean,
        acceleratorRunner: SupertonicRunnerBridge?,
    ): Long
    external fun nativeDestroySynthesizer(handle: Long)
    external fun nativeStopSynthesizer(handle: Long)
    external fun nativeSetSynthesizerVoice(handle: Long, voiceId: String)
    external fun nativeSetSynthesizerSpeed(handle: Long, speed: Float)
    external fun nativeSetSynthesizerSteps(handle: Long, totalSteps: Int)
    external fun nativeSetSynthesizerChunkCap(handle: Long, chunkCap: Int)
    external fun nativeSetSynthesizerPreGeneration(handle: Long, enabled: Boolean)
    external fun nativeSetSynthesizerPreGenerationQueue(handle: Long, depth: Int)
    external fun nativeSetSynthesizerChunkGap(handle: Long, minMs: Int, maxMs: Int)
    external fun nativeSetSynthesizerTrailingSilenceTrim(handle: Long, trimMs: Int)
    external fun nativeGetLastProfile(handle: Long): String
    external fun nativeSynthesizerSampleRate(handle: Long): Int
    external fun nativeSynthesize(handle: Long, text: String, language: String): ByteArray
    external fun nativeSynthesizeStreaming(handle: Long, text: String, language: String, callback: SynthesisCallback)

    fun interface SynthesisCallback { fun onChunk(audio: ByteArray, isFinal: Boolean) }
}
