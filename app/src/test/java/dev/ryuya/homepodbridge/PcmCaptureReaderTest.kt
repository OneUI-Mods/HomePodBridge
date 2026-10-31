package dev.ryuya.homepodbridge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Test

class PcmCaptureReaderTest {
    @Test
    @Throws(Exception::class)
    fun residualHalFramesAreReturnedWithoutWaitingForNextBurst() {
        val original = ByteArray(928 * 4 * 3)
        for (i in original.indices) original[i] = (i * 37).toByte()
        val position = intArrayOf(0)
        val available = intArrayOf(0)
        val bursts = intArrayOf(0)
        val capture =
            PcmCaptureReader(
                88 * 4,
                true,
                PcmCaptureReader.Reader { buffer: ByteBuffer?, bytes: Int, mode: Int ->
                    if (available[0] == 0 && mode == 0) {
                        assertEquals(4, bytes)
                        if (position[0] == original.size) return@Reader -1
                        available[0] = 928 * 4
                        bursts[0]++
                    }
                    val take = min(bytes, available[0])
                    for (i in 0 until take) buffer!!.put(i, original[position[0] + i])
                    available[0] -= take
                    position[0] += take
                    take
                },
            )
        val out = ByteArrayOutputStream()
        for (burst in 1..3) {
            for (i in 0..9) {
                assertEquals(352, capture.read())
                while (capture.buffer.hasRemaining()) out.write(capture.buffer.get().toInt())
            }
            assertEquals(192, capture.read())
            assertEquals(
                burst,
                bursts[0],
            ) // The 48-frame remainder did not wait for another HAL burst.
            while (capture.buffer.hasRemaining()) out.write(capture.buffer.get().toInt())
        }
        assertArrayEquals(original, out.toByteArray())
        assertEquals(-1, capture.read())
    }

    @Test
    @Throws(Exception::class)
    fun legacyReaderStillRequestsWholeBlocks() {
        val capture =
            PcmCaptureReader(
                352,
                false,
                PcmCaptureReader.Reader { buffer: ByteBuffer?, size: Int, mode: Int ->
                    assertEquals(352, size)
                    assertEquals(0, mode)
                    352
                },
            )
        assertEquals(352, capture.read())
        assertTrue(capture.buffer.isDirect())
    }

    @Test(expected = IOException::class)
    @Throws(Exception::class)
    fun nonStereoReadCannotReachThePipe() {
        PcmCaptureReader(
                352,
                true,
                PcmCaptureReader.Reader { buffer: ByteBuffer?, size: Int, mode: Int -> 3 },
            )
            .read()
    }
}
