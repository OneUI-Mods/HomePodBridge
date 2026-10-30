package dev.ryuya.homepodbridge

import org.junit.Assert.*
import org.junit.Test

class BufferLimitsTest {
    @Test
    fun customSettingsUseTheSameBoundsAsTheControls() {
        val low = StreamSettings(false, -1, -1, -1)
        assertEquals(BufferLimits.RESERVE_MIN_MS, low.bufferMs())
        assertEquals(BufferLimits.CAPTURE_MIN_MS, low.blockMs())
        assertEquals(BufferLimits.TIMING_MIN_MS, low.latencyMs())
        val high = StreamSettings(false, 9999, 9999, 9999)
        assertEquals(BufferLimits.RESERVE_MAX_MS, high.bufferMs())
        assertEquals(BufferLimits.CAPTURE_MAX_MS, high.blockMs())
        assertEquals(BufferLimits.TIMING_MAX_MS, high.latencyMs())
        assertEquals(BufferLimits.TIMING_MIN_MS, ReceiverTiming(true, -1, -1, -1).presentationMs())
    }

    @Test
    fun dedicatedUnitIsFixedAndCustomRequiresAnExplicitProfileChange() {
        val fast = StreamSettings(true, 0, 2, 50, true).withDedicatedCapture(true)
        assertEquals(
            (22 * 1000.0 / 44100).toDouble(),
            (fast.captureUnitMs()).toDouble(),
            (0.00001).toDouble(),
        )
        assertEquals(1, fast.profile())
        val custom = fast.withBuffers(0, 2, 50)
        assertEquals(0, custom.profile())
        assertFalse(custom.effectiveDedicatedCapture())
        assertEquals((2).toDouble(), (custom.captureUnitMs()).toDouble(), (0.00001).toDouble())
        assertTrue(custom.dedicatedCapture())
    }
}
