package dev.ryuya.homepodbridge

import org.junit.Assert.*
import org.junit.Test

class PipelineTimingTest {
    @Test
    fun halQueueIsSeparateFromUnknownCaptureAge() {
        val timing = PipelineTiming(null, null, 100, 2000000L, 800)
        assertEquals(800, timing.halQueuedFrames)
        assertEquals(-1, timing.capture(1))
        assertFalse(timing.captureAgeFresh())
        assertEquals(-1, PipelineTiming(null, null, -1, 1).halQueuedFrames)
    }

    @Test
    fun appendedProbeMetricsKeepTheExistingTimingPrefix() {
        val capture = longArrayOf(1, 61000, 1000000000L, 2000, 21000, 20, 50, 2784, 88, 1, 44100)
        val timing = PipelineTiming(capture, null, 100, 2000000000L)
        assertEquals(61000, timing.capture(1))
        assertEquals(1, timing.capture(9))
        assertTrue(timing.captureAgeFresh())
        assertEquals(-1, timing.capture(-1))
        assertEquals(-1, timing.capture(11))
    }

    @Test
    fun missingOrOldSchemaNeverBecomesZeroDelay() {
        val empty = PipelineTiming(null, null, -1, 1)
        assertEquals(-1, empty.capture(1))
        assertEquals(-1, empty.sender(0))
        assertFalse(empty.captureAgeFresh())
        val old = LongArray(10)
        old[0] = 2
        assertEquals(-1, PipelineTiming(old, LongArray(4), -1, 1).capture(1))
    }

    @Test
    fun staleAndFutureCaptureClocksAreNotLiveMeasurements() {
        val capture = longArrayOf(1, 82000, 1000000000L, 0, 0, 0, 0, 2784, 88, 0)
        assertTrue(PipelineTiming(capture, null, 100, 2000000000L).captureAgeFresh())
        assertFalse(PipelineTiming(capture, null, 100, 5000000001L).captureAgeFresh())
        assertFalse(PipelineTiming(capture, null, 100, 500000000L).captureAgeFresh())
    }
}
