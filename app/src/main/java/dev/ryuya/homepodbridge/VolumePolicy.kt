package dev.ryuya.homepodbridge

import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min

object VolumePolicy {
    @JvmStatic
    fun clamp(requested: Int, maximum: Int): Int {
        return max(0, min(min(100, maximum), requested))
    }

    @JvmStatic
    fun apply(requested: Int): Int {
        return VolumePolicy.clamp(requested, BuildConfig.VOLUME_LIMIT)
    }
}
