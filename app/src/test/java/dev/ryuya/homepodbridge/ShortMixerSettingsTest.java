package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ShortMixerSettingsTest {
  @Test
  public void requiresExplicitPreferenceAndDedicatedLowLatency() {
    var base = new StreamSettings(true, 0, 2, 50, true).withDedicatedCapture(true);
    assertFalse(base.shortMixerPeriod());
    var enabled = base.withShortMixerPeriod(true);
    assertTrue(enabled.effectiveShortMixerPeriod());
    assertFalse(enabled.withDedicatedCapture(false).effectiveShortMixerPeriod());
    assertFalse(enabled.withProfile(2).effectiveShortMixerPeriod());
    assertFalse(enabled.withShortMixerPeriod(false).effectiveShortMixerPeriod());
    assertTrue(enabled.withProfile(2).shortMixerPeriod());
  }

  @Test
  public void preservesOtherSettingsAndSurvivesEveryCopy() {
    var base =
        new StreamSettings(true, 0, 2, 50, true)
            .withDedicatedCapture(true)
            .withShortAudioPackets(true)
            .withShortMixerPeriod(true);
    var changed =
        base.withWholeDevice(false)
            .withCompression(true)
            .withAdaptiveBuffer(false)
            .withDeviceBuffer(true)
            .withDeviceBufferFrames(928)
            .withBuffers(0, 2, 40)
            .withReceiverTiming(new ReceiverTiming(true, 20, 80, 20))
            .withProfile(1)
            .withDedicatedCapture(true)
            .withShortAudioPackets(false);
    assertTrue(changed.shortMixerPeriod());
    assertFalse(changed.shortAudioPackets());
    assertTrue(changed.effectiveShortMixerPeriod());
    assertEquals(20, changed.requestedPresentationMs());
    assertEquals(base.captureUnitMs(), changed.captureUnitMs(), 0.00001);
  }
}
