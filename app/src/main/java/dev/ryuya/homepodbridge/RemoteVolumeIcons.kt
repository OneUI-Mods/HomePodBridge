package dev.ryuya.homepodbridge

import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.Synchronized

/* Only the current lease's dynamic row may toggle its own remembered audible volume. */
class RemoteVolumeIcons {
    @JvmField @Volatile var stream: Int = -1
    private var audible: Int = 1

    fun owns(requestedStream: Int): Boolean {
        return stream >= 100 && stream == requestedStream
    }

    @Synchronized
    fun target(requestedStream: Int, level: Int): Int? {
        if (!owns(requestedStream)) return null
        if (level > 0) {
            audible = VolumePolicy.apply(level)
            return 0
        }
        return audible
    }
}
