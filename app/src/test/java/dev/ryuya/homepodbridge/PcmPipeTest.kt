package dev.ryuya.homepodbridge

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.util.function.LongSupplier
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Test

class PcmPipeTest {
    @Test
    @Throws(Exception::class)
    fun availableHalPacketNeedsOneChannelReadWithoutWaitingForAnotherPacket() {
        val raw =
            ByteBuffer.allocate(112)
                .putInt(PcmPipe.HAL_MAGIC)
                .putInt(88)
                .putLong(1000000L)
                .putLong(22)
                .put(ByteArray(88))
                .array()
        val reads = intArrayOf(0)
        val channel: ReadableByteChannel? =
            object : ReadableByteChannel {
                override fun read(target: ByteBuffer): Int {
                    if (++reads[0] != 1) throw AssertionError("Blocked for a future record")
                    target.put(raw)
                    return raw.size
                }

                override fun isOpen(): Boolean {
                    return true
                }

                override fun close() {}
            }
        val pipe = PcmPipe(channel!!, true, LongSupplier { 2000000L }, PcmPipe.HAL_MAGIC)
        assertEquals(88, pipe.read(ByteBuffer.allocateDirect(176)))
        assertEquals(1, reads[0])
        assertEquals(22, pipe.pendingFrames())
    }

    @Test
    @Throws(Exception::class)
    fun halfMillisecondLiveRecordsKeepFramingAndNonzeroPcm() {
        val raw =
            ByteBuffer.allocate(224)
                .putInt(PcmPipe.HAL_MAGIC)
                .putInt(88)
                .putLong(1000000L)
                .putLong(22)
                .put(ByteArray(88))
                .putInt(PcmPipe.HAL_MAGIC)
                .putInt(88)
                .putLong(1000000L)
                .putLong(-1)
                .put(ByteArray(88))
                .array()
        raw[24] = 17
        val pipe =
            PcmPipe(
                Channels.newChannel(ByteArrayInputStream(raw)),
                true,
                LongSupplier { 2000000L },
                PcmPipe.HAL_MAGIC,
            )
        val pcm = ByteBuffer.allocateDirect(176)
        assertEquals(88, pipe.read(pcm))
        assertEquals(17, pcm.get(0).toInt())
        assertEquals(22, pipe.pendingFrames())
        assertEquals(88, pipe.read(pcm))
        assertEquals(0, pcm.get(0).toInt())
        assertEquals(-1, pipe.pendingFrames())
        assertEquals(-1, pipe.read(pcm))
    }

    @Test
    @Throws(Exception::class)
    fun liveIdleRecordsKeepTheConnectionOpenWithoutInventingCaptureDelay() {
        val raw = PcmPipeTest.halRecord(PcmPipe.HAL_MAGIC, 176, -1)
        val input =
            PcmPipe(
                Channels.newChannel(ByteArrayInputStream(raw)),
                true,
                LongSupplier { 2000000L },
                PcmPipe.HAL_MAGIC,
            )
        assertEquals(176, input.read(ByteBuffer.allocateDirect(176)))
        assertEquals(-1, input.pendingFrames())
    }

    @Test
    @Throws(Exception::class)
    fun halQueueMetadataSurvivesFragmentationWithoutEnteringPcm() {
        val input: ByteArrayInputStream? =
            object : ByteArrayInputStream(PcmPipeTest.halRecord(PcmPipe.HAL_ZERO_MAGIC, 176, 800)) {
                @Synchronized
                override fun read(out: ByteArray?, start: Int, count: Int): Int {
                    return super.read(out, start, min(count, 3))
                }
            }
        val pipe = PcmPipe(Channels.newChannel(input), true, LongSupplier { 1234000L }, true)
        val pcm = ByteBuffer.allocateDirect(352)
        assertEquals(176, pipe.read(pcm))
        assertEquals(800, pipe.pendingFrames())
        assertEquals(234, pipe.transitMicros())
        val bytes = ByteArray(176)
        pcm.get(bytes)
        assertArrayEquals(ByteArray(176), bytes)
        assertEquals(-1, pipe.read(pcm))
    }

    @Test
    @Throws(Exception::class)
    fun halAndLegacyRecordsCannotBeConfused() {
        for (raw in
            arrayOf<ByteArray?>(
                PcmPipeTest.halRecord(PcmPipe.MAGIC, 176, 0),
                PcmPipeTest.halRecord(PcmPipe.HAL_ZERO_MAGIC, 4, 0),
                PcmPipeTest.halRecord(PcmPipe.HAL_ZERO_MAGIC, 176, -1),
                PcmPipeTest.halRecord(PcmPipe.HAL_ZERO_MAGIC, 176, 4097),
            )) {
            try {
                PcmPipe(
                        Channels.newChannel(ByteArrayInputStream(raw)),
                        true,
                        LongSupplier { 2000000L },
                        true,
                    )
                    .read(ByteBuffer.allocateDirect(352))
                fail("Invalid HAL frame accepted")
            } catch (expected: IOException) {}
        }
        try {
            PcmPipe(
                    Channels.newChannel(
                        ByteArrayInputStream(PcmPipeTest.halRecord(PcmPipe.HAL_ZERO_MAGIC, 176, 0))
                    ),
                    true,
                )
                .read(ByteBuffer.allocateDirect(352))
            fail("HAL frame accepted by legacy decoder")
        } catch (expected: IOException) {}
    }

    @Test
    @Throws(Exception::class)
    fun fragmentedReadsPreserveStereoFramesAndEndian() {
        val raw = byteArrayOf(0, 128.toByte(), 255.toByte(), 127, 1, 0, 255.toByte(), 255.toByte())
        val input: ByteArrayInputStream? =
            object : ByteArrayInputStream(raw) {
                @Synchronized
                override fun read(bytes: ByteArray?, offset: Int, length: Int): Int {
                    return super.read(bytes, offset, min(length, 3))
                }
            }
        val pipe = PcmPipe(Channels.newChannel(input))
        val pcm = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(8, pipe.read(pcm))
        assertTrue(pcm.isDirect())
        val samples = ShortArray(4)
        pcm.asShortBuffer().get(samples)
        assertArrayEquals(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, 1, -1), samples)
        assertEquals(-1, pipe.read(pcm))
    }

    @Test(expected = IOException::class)
    @Throws(Exception::class)
    fun partialFrameAtEofIsRejected() {
        PcmPipe(Channels.newChannel(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
            .read(ByteBuffer.allocateDirect(8))
    }

    @Test
    @Throws(Exception::class)
    fun bufferIsReusedAndFinalWholeFramesStayAligned() {
        val pipe = PcmPipe(Channels.newChannel(ByteArrayInputStream(ByteArray(12))))
        val pcm = ByteBuffer.allocateDirect(8)
        assertEquals(8, pipe.read(pcm))
        assertEquals(4, pipe.read(pcm))
        assertEquals(0, pcm.position())
        assertEquals(4, pcm.limit())
        assertEquals(-1, pipe.read(pcm))
    }

    @Test
    @Throws(Exception::class)
    fun timedFragmentsKeepHeaderOutOfPcmAndMeasureMatchingBlock() {
        val pcm = byteArrayOf(0, 128.toByte(), 255.toByte(), 127, 1, 0, 255.toByte(), 255.toByte())
        val input: ByteArrayInputStream? =
            object : ByteArrayInputStream(PcmPipeTest.timed(PcmPipe.MAGIC, 8, 1000000, pcm)) {
                @Synchronized
                override fun read(out: ByteArray?, offset: Int, length: Int): Int {
                    return super.read(out, offset, min(3, length))
                }
            }
        val pipe = PcmPipe(Channels.newChannel(input), true, LongSupplier { 1234000L })
        val buffer = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(8, pipe.read(buffer))
        val actual = ByteArray(8)
        buffer.get(actual)
        assertArrayEquals(pcm, actual)
        assertEquals(234, pipe.transitMicros())
        assertEquals(-1, pipe.read(buffer))
    }

    @Test
    @Throws(Exception::class)
    fun malformedTimingAndPartialPayloadFailClosed() {
        val invalid =
            arrayOf<ByteArray?>(
                byteArrayOf(1, 2, 3),
                PcmPipeTest.timed(0, 4, 1, ByteArray(4)),
                PcmPipeTest.timed(PcmPipe.MAGIC, 3, 1, ByteArray(3)),
                PcmPipeTest.timed(PcmPipe.MAGIC, 20, 1, ByteArray(20)),
                PcmPipeTest.timed(PcmPipe.MAGIC, 0, 1, ByteArray(0)),
                PcmPipeTest.timed(PcmPipe.MAGIC, 8, 1, ByteArray(4)),
            )
        for (raw in invalid) {
            try {
                PcmPipe(Channels.newChannel(ByteArrayInputStream(raw)), true)
                    .read(ByteBuffer.allocateDirect(16))
                fail("Malformed timed block accepted")
            } catch (expected: IOException) {}
        }
    }

    @Test
    @Throws(Exception::class)
    fun futureTimestampIsUnknownRatherThanNegativeLatency() {
        val pipe =
            PcmPipe(
                Channels.newChannel(
                    ByteArrayInputStream(PcmPipeTest.timed(PcmPipe.MAGIC, 4, 10000, ByteArray(4)))
                ),
                true,
                LongSupplier { 9000 },
            )
        assertEquals(4, pipe.read(ByteBuffer.allocateDirect(4)))
        assertEquals(-1, pipe.transitMicros())
    }

    companion object {
        @JvmStatic
        private fun halRecord(magic: Int, length: Int, pending: Long): ByteArray? {
            return ByteBuffer.allocate(200)
                .putInt(magic)
                .putInt(length)
                .putLong(1000000L)
                .putLong(pending)
                .put(ByteArray(176))
                .array()
        }

        @JvmStatic
        private fun timed(magic: Int, length: Int, stamp: Long, pcm: ByteArray): ByteArray? {
            return ByteBuffer.allocate(16 + pcm.size)
                .putInt(magic)
                .putInt(length)
                .putLong(stamp)
                .put(pcm)
                .array()
        }
    }
}
