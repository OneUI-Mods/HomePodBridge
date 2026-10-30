package dev.ryuya.homepodbridge

import java.nio.ByteBuffer
import kotlin.jvm.JvmStatic

/*
 * Explicit diagnostic path only: retain payload length/timing and send exclusively zero samples.
 */
object ZeroProbePcm {
    @JvmStatic
    fun clear(pcm: ByteBuffer, bytes: Int) {
        require(!(bytes <= 0 || bytes % 4 != 0 || bytes > pcm.capacity())) {
            "Invalid stereo payload"
        }
        var i: Int = 0
        while (i < bytes) {
            pcm.putInt(i, 0)
            i += 4
        }
    }
}
