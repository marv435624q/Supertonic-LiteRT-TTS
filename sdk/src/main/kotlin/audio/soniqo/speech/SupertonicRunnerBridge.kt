package audio.soniqo.speech

import java.nio.ByteBuffer

/**
 * Stable JVM ABI consumed by the native JniSupertonicRunner.
 *
 * Accelerator runners normally return null from cloneForPreGeneration because
 * accelerator pre-generation remains disabled. Implementations may return a fresh runner so
 * every speculative worker owns an independent ONNX Runtime VE session.
 */
internal interface SupertonicRunnerBridge : AutoCloseable {
    fun hasDuration(): Boolean
    fun hasEncoder(): Boolean
    fun hasVector(): Boolean
    fun hasVocoder(): Boolean
    fun backendReport(): String
    fun cloneForPreGeneration(): SupertonicRunnerBridge?

    fun runDuration(
        ids: ByteBuffer,
        styleDp: ByteBuffer,
        mask: ByteBuffer,
        out: ByteBuffer,
    )

    fun runEncoder(
        ids: ByteBuffer,
        styleTtl: ByteBuffer,
        mask: ByteBuffer,
        out: ByteBuffer,
    )

    fun runVector(
        noisyLatent: ByteBuffer,
        textEmb: ByteBuffer,
        styleTtl: ByteBuffer,
        latentMask: ByteBuffer,
        textMask: ByteBuffer,
        currentStep: ByteBuffer,
        totalStep: ByteBuffer,
        out: ByteBuffer,
    )

    fun runVocoder(
        latent: ByteBuffer,
        out: ByteBuffer,
    )
}
