package dev.ryuya.homepodbridge

import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min

/* Independent negotiation and RTP presentation settings; neither value is measured latency. */

class ReceiverTiming(
    independent: Boolean,
    minimumMs: Int,
    maximumMs: Int,
    presentationMs: Int,
) {
    val independent: Boolean
    val minimumMs: Int
    val maximumMs: Int
    val presentationMs: Int

    init {

        var minimumMs: Int = minimumMs
        var maximumMs: Int = maximumMs
        var presentationMs: Int = presentationMs
        minimumMs = ReceiverTiming.clamp(minimumMs)
        maximumMs = max(minimumMs, ReceiverTiming.clamp(maximumMs))
        presentationMs = ReceiverTiming.clamp(presentationMs)

        this.independent = independent
        this.minimumMs = minimumMs
        this.maximumMs = maximumMs
        this.presentationMs = presentationMs
    }

    companion object {
        @JvmStatic
        fun linked(targetMs: Int): ReceiverTiming? {
            return ReceiverTiming(false, min(targetMs, 250), targetMs, targetMs)
        }

        @JvmStatic
        private fun clamp(value: Int): Int {
            return BufferLimits.clamp(value, BufferLimits.TIMING_MIN_MS, BufferLimits.TIMING_MAX_MS)
        }
    }

    fun independent(): Boolean = independent

    fun minimumMs(): Int = minimumMs

    fun maximumMs(): Int = maximumMs

    fun presentationMs(): Int = presentationMs

    override fun equals(other: Any?): Boolean =
        other is ReceiverTiming &&
            independent == other.independent &&
            minimumMs == other.minimumMs &&
            maximumMs == other.maximumMs &&
            presentationMs == other.presentationMs

    override fun hashCode(): Int {
        var result = independent.hashCode()
        result = 31 * result + minimumMs.hashCode()
        result = 31 * result + maximumMs.hashCode()
        result = 31 * result + presentationMs.hashCode()
        return result
    }

    override fun toString(): String =
        "ReceiverTiming[independent=$independent, minimumMs=$minimumMs, maximumMs=$maximumMs, presentationMs=$presentationMs]"
}
