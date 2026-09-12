package audio.soniqo.speech.audio

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
