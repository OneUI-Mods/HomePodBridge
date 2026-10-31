package dev.ryuya.homepodbridge

import dev.ryuya.homepodbridge.halprobe.PhoneTiming
import org.junit.Assert.*
import org.junit.Test

class ManualSettingsTest {
    @Test
    fun manualNumbersAreNotOverriddenByProfilesOrAdaptiveLogic() {
        val s =
            StreamSettings(true, 37, 7, 123)
                .withProfile(3)
                .withDedicatedCapture(true)
                .withAdaptiveBuffer(true)
                .withPhoneTiming(PhoneTiming(11, 447))
                .withReceiverTiming(ReceiverTiming(true, 25, 180, 123))
                .withShortAudioPackets(true)
        assertEquals(3, s.profile())
        assertTrue(s.manualMode())
        assertTrue(s.effectiveDedicatedCapture())
        assertTrue(s.effectiveShortMixerPeriod())
        assertFalse(s.effectiveAdaptiveBuffer())
        assertEquals(37, s.effectiveBufferMs())
        assertEquals(7, s.effectiveBlockMs())
        assertEquals(123, s.requestedPresentationMs())
        assertEquals(25, s.requestedReceiverMinimumMs())
        assertEquals(180, s.requestedReceiverMaximumMs())
        assertEquals(88, s.packetFramesForBackend(true))
        assertEquals(352, s.packetFramesForBackend(false))
        val copy =
            s.withWholeDevice(false)
                .withCompression(true)
                .withDeviceBuffer(true)
                .withDeviceBufferFrames(928)
                .withShortMixerPeriod(false)
                .withShortAudioPackets(false)
                .withBuffers(19, 3, 77)
        assertTrue(copy.manualMode())
        assertEquals(PhoneTiming(11, 447), copy.phoneTiming())
        assertEquals(19, copy.effectiveBufferMs())
        assertEquals(3, copy.effectiveBlockMs())
        assertEquals(PhoneTiming(11, 447), copy.withProfile(2).withProfile(3).phoneTiming())
    }

    @Test
    fun manualSnapshotStartsFromEffectiveValues() {
        val low =
            StreamSettings(false, 150, 10, 1500, true)
                .withDedicatedCapture(true)
                .withShortMixerPeriod(true)
        val s = low.asManual()
        assertTrue(s.manualMode())
        assertEquals(low.effectiveBufferMs(), s.effectiveBufferMs())
        assertEquals(low.effectiveBlockMs(), s.effectiveBlockMs())
        assertEquals(50, s.requestedPresentationMs())
        assertEquals(PhoneTiming.fastest(), s.phoneTiming())
        assertTrue(s.effectiveDedicatedCapture())
        assertFalse(s.effectiveAdaptiveBuffer())
        val standard = low.withShortMixerPeriod(false).asManual()
        assertEquals(PhoneTiming(21, 800), standard.phoneTiming())
    }

    @Test
    fun presetsAreExclusiveAndDoNotEnableShortPackets() {
        val base =
            StreamSettings(true, 19, 4, 87).withProfile(3).withPhoneTiming(PhoneTiming(8, 350))
        val low = base.preset(1)
        assertEquals(1, low.profile())
        assertFalse(low.manualMode())
        assertTrue(low.effectiveDedicatedCapture())
        assertTrue(low.effectiveShortMixerPeriod())
        assertFalse(low.effectiveShortAudioPackets())
        assertFalse(low.effectiveAdaptiveBuffer())
        assertFalse(low.compressedAudio())
        assertEquals(0, low.effectiveBufferMs())
        assertEquals(50, low.requestedPresentationMs())
        val balanced = base.preset(0)
        assertEquals(0, balanced.profile())
        assertEquals(150, balanced.effectiveBufferMs())
        assertEquals(1500, balanced.requestedPresentationMs())
        assertFalse(balanced.effectiveDedicatedCapture())
        val quality = base.preset(2)
        assertEquals(2, quality.profile())
        assertEquals(1500, quality.requestedPresentationMs())
        assertFalse(quality.effectiveShortMixerPeriod())
        assertFalse(quality.compressedAudio())
    }

    @Test
    fun phoneBoundsAndQuantizationAreExplicit() {
        assertEquals(48, PhoneTiming(1, 64).periodFrames())
        assertEquals(96, PhoneTiming(2, 96).periodFrames())
        assertEquals(928, PhoneTiming(21, 800).periodFrames())
        assertEquals(96, PhoneTiming.fastest().latencyWindowFrames())
        var previous: Int = 0
        for (i in 1..21) {
            val t = PhoneTiming(i, 400)
            assertTrue(t.periodFrames() > previous)
            previous = t.periodFrames()
            assertEquals(0, t.periodFrames() % 16)
        }
        assertEquals(2, PhoneTiming.nearest(2.18, 96).periodMs)
        assertEquals(1, PhoneTiming.nearest(1.09, 64).periodMs)
        assertEquals(21, PhoneTiming.nearest(21.04, 800).periodMs)
        assertEquals(PhoneTiming.fastest(), PhoneTiming.fromStored(-1, -1))
        assertEquals(
            PhoneTiming(21, 800),
            PhoneTiming.fromStored(Int.MAX_VALUE, Int.MAX_VALUE),
        )
        for (x in
            doubleArrayOf(
                (Float.NaN).toDouble(),
                (Float.POSITIVE_INFINITY).toDouble(),
                1.08,
                21.05,
            )) assertThrows(
            IllegalArgumentException::class.java,
            { PhoneTiming.nearest(x, 96) },
        )
        assertThrows(IllegalArgumentException::class.java, { PhoneTiming(0, 64) })
        assertThrows(IllegalArgumentException::class.java, { PhoneTiming(22, 96) })
        assertThrows(IllegalArgumentException::class.java, { PhoneTiming(1, 63) })
        assertThrows(IllegalArgumentException::class.java, { PhoneTiming(2, 801) })
    }
}
