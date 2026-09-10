package audio.soniqo.speech.audio

import sonic.Sonic
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pitch-preserving speech-rate adjustment using the bundled AOSP Sonic implementation. */
object AudioSpeedProcessor {
    data class Result(val pcm16: ByteArray, val processingMs: Double)

    /**
     * Stateful Sonic stream used by Android TTS streaming.
     *
     * The TTS model still runs at 1.0x.  A single Sonic instance is kept for the
     * entire utterance, so chunk boundaries do not reset the time-scale algorithm.
     * This preserves the old whole-wave post-processing semantics while allowing
     * already-produced PCM to reach Android before the complete utterance exists.
     */
    class Stream(sampleRate: Int, speed: Float) {
        private val sonic = Sonic(sampleRate, 1)
        private var finished = false
        var processingMs: Double = 0.0
            private set

        init {
            val clamped = speed.coerceIn(0.25f, 3.0f)
            sonic.setSpeed(clamped)
            sonic.setPitch(1.0f)
            sonic.setRate(1.0f)
            sonic.setVolume(1.0f)
            sonic.setChordPitch(false)
            sonic.setQuality(0)
        }

        fun process(pcm16: ByteArray, final: Boolean = false): ByteArray {
            check(!finished) { "AudioSpeedProcessor.Stream is already finished" }
            val start = System.nanoTime()
            if (pcm16.isNotEmpty()) {
                require(pcm16.size % 2 == 0) { "PCM16 must contain complete samples" }
                val input = ShortArray(pcm16.size / 2)
                ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(input)
                sonic.writeShortToStream(input, input.size)
            }
            if (final) {
                sonic.flushStream()
                finished = true
            }

            val chunks = ArrayList<ShortArray>()
            var totalRead = 0
            while (true) {
                val available = sonic.samplesAvailable()
                if (available <= 0) break
                val buffer = ShortArray(available)
                val read = sonic.readShortFromStream(buffer, available)
                if (read <= 0) break
                chunks.add(if (read == buffer.size) buffer else buffer.copyOf(read))
                totalRead += read
            }
            val bytes = ByteArray(totalRead * 2)
            if (totalRead > 0) {
                val out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (chunk in chunks) out.put(chunk)
            }
            processingMs += (System.nanoTime() - start) / 1_000_000.0
            return bytes
        }
    }

    /** Whole-wave helper retained for app benchmark/export paths. */
    fun apply(pcm16: ByteArray, sampleRate: Int, speed: Float): Result {
        val start = System.nanoTime()
        val clamped = speed.coerceIn(0.25f, 3.0f)
        if (pcm16.isEmpty() || kotlin.math.abs(clamped - 1.0f) < 0.001f) {
            return Result(pcm16, (System.nanoTime() - start) / 1_000_000.0)
        }
        val stream = Stream(sampleRate, clamped)
        val bytes = stream.process(pcm16, final = true)
        return Result(bytes, (System.nanoTime() - start) / 1_000_000.0)
    }
}
