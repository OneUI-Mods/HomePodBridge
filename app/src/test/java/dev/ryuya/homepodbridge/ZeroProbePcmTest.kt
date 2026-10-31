package dev.ryuya.homepodbridge

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ZeroProbePcmTest {
    @Test
    fun exactPayloadBecomesSilentWithoutMovingBufferCursorOrTouchingTail() {
        val pcm = ByteBuffer.allocateDirect(32)
        for (i in 0..31) pcm.put(i, 0x5a.toByte())
        pcm.position(3)
        ZeroProbePcm.clear(pcm, 20)
        assertEquals(3, pcm.position())
        assertEquals(32, pcm.limit())
        for (i in 0..19) assertEquals(0.toByte(), pcm.get(i))
        for (i in 20..31) assertEquals(0x5a.toByte(), pcm.get(i))
    }

    @Test
    fun incompleteFramesAndOversizedPayloadsAreRejected() {
        for (bytes in intArrayOf(0, -4, 3, 17, 20)) {
            val pcm = ByteBuffer.allocateDirect(16)
            try {
                ZeroProbePcm.clear(pcm, bytes)
                fail()
            } catch (expected: IllegalArgumentException) {}
        }
    }
}
