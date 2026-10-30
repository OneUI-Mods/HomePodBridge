package dev.ryuya.homepodbridge

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmRecord

/* Numeric diagnostics only; no PCM, receiver addresses, credentials or acoustic-delay claim. */
@JvmRecord
data class PipelineTiming
@JvmOverloads
constructor(
    val capture: LongArray?,
    val sender: LongArray?,
    val pipeMicros: Long,
    val sampledAtNanos: Long,
    val halQueuedFrames: Long = -1,
) {
    fun capture(index: Int): Long {
        return if (
            capture != null &&
                capture.size >= 10 &&
                capture[0] == 1L &&
                index >= 0 &&
                index < capture.size
        )
            capture[index]
        else -1
    }

    fun sender(index: Int): Long {
        return if (sender != null && sender.size === 13) sender[index] else -1
    }

    fun captureAgeFresh(): Boolean {
        val at = capture(2)
        return at > 0 && sampledAtNanos >= at && sampledAtNanos - at <= 3000000000L
    }

    override fun toString(): String =
        "PipelineTiming[capture=$capture, sender=$sender, pipeMicros=$pipeMicros, sampledAtNanos=$sampledAtNanos, halQueuedFrames=$halQueuedFrames]"

    override fun hashCode(): Int {
        var result = 0
        result = 31 * result + capture.hashCode()
        result = 31 * result + sender.hashCode()
        result = 31 * result + pipeMicros.hashCode()
        result = 31 * result + sampledAtNanos.hashCode()
        result = 31 * result + halQueuedFrames.hashCode()
        return result
    }
}
