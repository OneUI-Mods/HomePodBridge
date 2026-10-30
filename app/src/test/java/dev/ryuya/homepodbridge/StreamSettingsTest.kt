package dev.ryuya.homepodbridge

import org.junit.Assert
import org.junit.Assert.*
import org.junit.Test

class StreamSettingsTest {
    @Test
    fun dedicatedPreferenceSurvivesProfileAndSourceChanges() {
        val value = StreamSettings(false, 0, 2, 50, true).withDedicatedCapture(true)
        Assert.assertTrue(value.effectiveDedicatedCapture())
        Assert.assertTrue(
            value
                .withWholeDevice(true)
                .withCompression(true)
                .withDeviceBuffer(true)
                .withDeviceBufferFrames(928)
                .withAdaptiveBuffer(false)
                .dedicatedCapture()
        )
        Assert.assertTrue(value.withProfile(2).dedicatedCapture())
        Assert.assertFalse(value.withProfile(2).effectiveDedicatedCapture())
        Assert.assertTrue(value.withBuffers(0, 2, 50).dedicatedCapture())
    }

    @Test
    fun smallerReserveRemainsOptionalAndSurvivesOtherSettings() {
        val balanced = StreamSettings(true, 0, 2, 50, true).withDeviceBuffer(true)
        assertEquals(1856, balanced.deviceBufferFrames())
        val faster = balanced.withDeviceBufferFrames(928)
        assertEquals(
            928,
            faster
                .withCompression(true)
                .withAdaptiveBuffer(false)
                .withWholeDevice(false)
                .withProfile(2)
                .withProfile(1)
                .deviceBufferFrames(),
        )
        assertFalse(faster.withProfile(2).effectiveDeviceBuffer())
        assertEquals(928, faster.withBuffers(0, 2, 50).deviceBufferFrames())
        assertEquals(928, faster.withDeviceBuffer(false).deviceBufferFrames())
        assertEquals(1856, faster.withDeviceBufferFrames(464).deviceBufferFrames())
        assertEquals(1856, faster.withDeviceBufferFrames(0).deviceBufferFrames())
        assertEquals(50, faster.effectiveLatencyMs())
    }

    @Test
    fun deviceBufferIsOptInAndOnlyEffectiveInLowLatency() {
        val custom = StreamSettings(true, 70, 20, 320)
        assertFalse(custom.deviceBuffer())
        val enabled = custom.withDeviceBuffer(true)
        assertFalse(enabled.effectiveDeviceBuffer())
        assertTrue(enabled.withProfile(1).effectiveDeviceBuffer())
        assertFalse(enabled.withProfile(2).effectiveDeviceBuffer())
        val changed =
            enabled
                .withProfile(1)
                .withAdaptiveBuffer(false)
                .withCompression(true)
                .withWholeDevice(false)
        assertTrue(changed.deviceBuffer())
        assertFalse(changed.withDeviceBuffer(false).effectiveDeviceBuffer())
        assertTrue(changed.withBuffers(0, 2, 50).deviceBuffer())
        assertEquals(enabled, enabled.withProfile(1).withProfile(0))
    }

    @Test
    fun unsafePreferencesAreBounded() {
        val low = StreamSettings(false, -1, -1, -1)
        assertEquals(0, low.bufferMs())
        assertEquals(2, low.blockMs())
        assertEquals(10, low.latencyMs())
        val high = StreamSettings(true, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(300, high.bufferMs())
        assertEquals(40, high.blockMs())
        assertEquals(2000, high.latencyMs())
        assertTrue(high.wholeDevice())
    }

    @Test
    fun everyBlockContainsWholeStereoFrames() {
        for (ms in 2..40) {
            val samples = StreamSettings(false, 150, ms, 1500).samplesPerRead()
            assertEquals(0, samples % 2)
            assertTrue(samples > 0)
            assertEquals(44100 * ms / 1000, samples / 2)
        }
    }

    @Test
    fun lowLatencyPreservesCustomSettingsForSwitchingBack() {
        val low = StreamSettings(true, 150, 10, 1500, true)
        assertEquals(8, low.effectiveBufferMs())
        assertTrue(low.effectiveAdaptiveBuffer())
        assertEquals(0, low.withAdaptiveBuffer(false).effectiveBufferMs())
        assertFalse(low.withAdaptiveBuffer(false).effectiveAdaptiveBuffer())
        assertEquals(2, low.effectiveBlockMs())
        assertEquals(50, low.effectiveLatencyMs())
        assertEquals(176, low.samplesPerRead())
        assertEquals(150, low.bufferMs())
        assertEquals(10, low.blockMs())
        assertEquals(1500, low.latencyMs())
    }

    @Test
    fun existingLowLatencyPresetUsesAudibleTargetWhileCustomExperimentsStayExplicit() {
        val existing = StreamSettings(true, 0, 2, 40, true, false, true, false)
        assertEquals(50, existing.effectiveLatencyMs())
        assertEquals(0, existing.effectiveBufferMs())
        assertEquals(2, existing.effectiveBlockMs())
        assertTrue(existing.compressedAudio())
        val custom = existing.withProfile(0)
        assertEquals(40, custom.effectiveLatencyMs())
        assertEquals(50, custom.withProfile(1).effectiveLatencyMs())
    }

    @Test
    fun profilesAndCompressionAreIndependentAndReversible() {
        val custom = StreamSettings(true, 70, 20, 320).withCompression(true)
        val quality = custom.withProfile(2)
        assertTrue(quality.highQuality())
        assertFalse(quality.effectiveAdaptiveBuffer())
        assertFalse(quality.lowLatency())
        assertEquals(150, quality.effectiveBufferMs())
        assertEquals(10, quality.effectiveBlockMs())
        assertEquals(1500, quality.effectiveLatencyMs())
        val low = quality.withProfile(1)
        assertTrue(low.lowLatency())
        assertFalse(low.highQuality())
        assertTrue(low.compressedAudio())
        assertFalse(low.withCompression(false).compressedAudio())
        assertTrue(low.withCompression(false).lowLatency())
        assertEquals(custom, low.withProfile(0))
        assertTrue(quality.withWholeDevice(false).highQuality())
        assertTrue(quality.withWholeDevice(false).compressedAudio())
        val tuned = quality.withBuffers(0, 5, 20)
        assertFalse(tuned.lowLatency())
        assertFalse(tuned.highQuality())
        assertTrue(tuned.compressedAudio())
        assertEquals(20, tuned.effectiveLatencyMs())
    }
}
