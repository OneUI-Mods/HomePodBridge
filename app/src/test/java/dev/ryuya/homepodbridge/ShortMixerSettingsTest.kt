package dev.ryuya.homepodbridge

import org.junit.Assert.*
import org.junit.Test

class ShortMixerSettingsTest {
    @Test
    fun requiresExplicitPreferenceAndDedicatedLowLatency() {
        val base = StreamSettings(true, 0, 2, 50, true).withDedicatedCapture(true)
        assertFalse(base.shortMixerPeriod())
        val enabled = base.withShortMixerPeriod(true)
        assertTrue(enabled.effectiveShortMixerPeriod())
        assertFalse(enabled.withDedicatedCapture(false).effectiveShortMixerPeriod())
        assertFalse(enabled.withProfile(2).effectiveShortMixerPeriod())
        assertFalse(enabled.withShortMixerPeriod(false).effectiveShortMixerPeriod())
        assertTrue(enabled.withProfile(2).shortMixerPeriod())
    }

    @Test
    fun preservesOtherSettingsAndSurvivesEveryCopy() {
        val base =
            StreamSettings(true, 0, 2, 50, true)
                .withDedicatedCapture(true)
                .withShortAudioPackets(true)
                .withShortMixerPeriod(true)
        val changed =
            base
                .withWholeDevice(false)
                .withCompression(true)
                .withAdaptiveBuffer(false)
                .withDeviceBuffer(true)
                .withDeviceBufferFrames(928)
                .withBuffers(0, 2, 40)
                .withReceiverTiming(ReceiverTiming(true, 20, 80, 20))
                .withProfile(1)
                .withDedicatedCapture(true)
                .withShortAudioPackets(false)
        assertTrue(changed.shortMixerPeriod())
        assertFalse(changed.shortAudioPackets())
        assertTrue(changed.effectiveShortMixerPeriod())
        assertEquals(20, changed.requestedPresentationMs())
        assertEquals(
            (base.captureUnitMs()).toDouble(),
            (changed.captureUnitMs()).toDouble(),
            (0.00001).toDouble(),
        )
    }
}
