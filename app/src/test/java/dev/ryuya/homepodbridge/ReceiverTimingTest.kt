package dev.ryuya.homepodbridge

import org.junit.Assert.*
import org.junit.Test

class ReceiverTimingTest {
    @Test
    fun existingProfilesKeepTheirLinkedTargets() {
        val low = StreamSettings(true, 0, 2, 40, true)
        assertEquals(50, low.requestedPresentationMs())
        assertEquals(50, low.requestedReceiverMinimumMs())
        assertEquals(50, low.requestedReceiverMaximumMs())
        val quality = low.withProfile(2)
        assertEquals(1500, quality.requestedPresentationMs())
        assertEquals(250, quality.requestedReceiverMinimumMs())
        assertEquals(1500, quality.requestedReceiverMaximumMs())
    }

    @Test
    fun independentTimingDoesNotDisableDedicatedCaptureOrChangeItsOtherSettings() {
        val original = StreamSettings(true, 0, 2, 50, true).withDedicatedCapture(true)
        val split = original.withReceiverTiming(ReceiverTiming(true, 50, 80, 20))
        assertTrue(split.effectiveDedicatedCapture())
        assertEquals(original.effectiveBufferMs(), split.effectiveBufferMs())
        assertEquals(50, split.requestedReceiverMinimumMs())
        assertEquals(80, split.requestedReceiverMaximumMs())
        assertEquals(20, split.requestedPresentationMs())
        assertEquals(
            split.receiverTiming(),
            split
                .withWholeDevice(false)
                .withCompression(true)
                .withAdaptiveBuffer(false)
                .withDeviceBuffer(true)
                .withDeviceBufferFrames(928)
                .withDedicatedCapture(false)
                .withBuffers(0, 2, 40)
                .withProfile(2)
                .receiverTiming(),
        )
        assertEquals(20, split.withProfile(2).requestedPresentationMs())
    }

    @Test
    fun disablingIndependentTimingRestoresTheCurrentProfile() {
        val split =
            StreamSettings(false, 0, 2, 40, true)
                .withReceiverTiming(ReceiverTiming(true, 50, 2000, 10))
        val linked = split.withReceiverTiming(ReceiverTiming(false, 50, 2000, 10))
        assertEquals(50, linked.requestedPresentationMs())
        assertEquals(50, linked.requestedReceiverMaximumMs())
        assertEquals(40, linked.withProfile(0).requestedPresentationMs())
    }

    @Test
    fun corruptedStoredTimingCannotInvertOrOverflowTheWindow() {
        assertEquals(
            ReceiverTiming(true, 10, 2000, 2000),
            ReceiverTiming(true, -1, Int.MAX_VALUE, Int.MAX_VALUE),
        )
        assertEquals(2000, ReceiverTiming(true, 2000, 10, 20).maximumMs())
    }
}
