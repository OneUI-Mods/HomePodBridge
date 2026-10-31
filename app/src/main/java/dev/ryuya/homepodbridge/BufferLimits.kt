package dev.ryuya.homepodbridge

import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min

/* Validated application limits shared by stored settings and controls, not acoustic guarantees. */
object BufferLimits {
    const val RESERVE_MIN_MS: Int = 0
    const val RESERVE_MAX_MS: Int = 300
    const val CAPTURE_MIN_MS: Int = 2
    const val CAPTURE_MAX_MS: Int = 40
    const val TIMING_MIN_MS: Int = 10
    const val TIMING_MAX_MS: Int = 2000
    const val DEDICATED_FRAMES: Int = 22

    @JvmStatic
    fun clamp(value: Int, minimum: Int, maximum: Int): Int {
        return max(minimum, min(maximum, value))
    }
}
