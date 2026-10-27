package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import dev.ryuya.homepodbridge.halprobe.PhoneTiming;
import org.junit.Test;

public final class ManualSettingsTest {
  @Test
  public void manualNumbersAreNotOverriddenByProfilesOrAdaptiveLogic() {
    var s =
        new StreamSettings(true, 37, 7, 123)
            .withProfile(3)
            .withDedicatedCapture(true)
            .withAdaptiveBuffer(true)
            .withPhoneTiming(new PhoneTiming(11, 447))
            .withReceiverTiming(new ReceiverTiming(true, 25, 180, 123))
            .withShortAudioPackets(true);
    assertEquals(3, s.profile());
    assertTrue(s.manualMode());
    assertTrue(s.effectiveDedicatedCapture());
    assertTrue(s.effectiveShortMixerPeriod());
    assertFalse(s.effectiveAdaptiveBuffer());
    assertEquals(37, s.effectiveBufferMs());
    assertEquals(7, s.effectiveBlockMs());
    assertEquals(123, s.requestedPresentationMs());
    assertEquals(25, s.requestedReceiverMinimumMs());
    assertEquals(180, s.requestedReceiverMaximumMs());
    assertEquals(88, s.packetFramesForBackend(true));
    assertEquals(352, s.packetFramesForBackend(false));
    var copy =
        s.withWholeDevice(false)
            .withCompression(true)
            .withDeviceBuffer(true)
            .withDeviceBufferFrames(928)
            .withShortMixerPeriod(false)
            .withShortAudioPackets(false)
            .withBuffers(19, 3, 77);
    assertTrue(copy.manualMode());
    assertEquals(new PhoneTiming(11, 447), copy.phoneTiming());
    assertEquals(19, copy.effectiveBufferMs());
    assertEquals(3, copy.effectiveBlockMs());
    assertEquals(new PhoneTiming(11, 447), copy.withProfile(2).withProfile(3).phoneTiming());
  }

  @Test
  public void manualSnapshotStartsFromEffectiveValues() {
    var low =
        new StreamSettings(false, 150, 10, 1500, true)
            .withDedicatedCapture(true)
            .withShortMixerPeriod(true);
    var s = low.asManual();
    assertTrue(s.manualMode());
    assertEquals(low.effectiveBufferMs(), s.effectiveBufferMs());
    assertEquals(low.effectiveBlockMs(), s.effectiveBlockMs());
    assertEquals(50, s.requestedPresentationMs());
    assertEquals(PhoneTiming.fastest(), s.phoneTiming());
    assertTrue(s.effectiveDedicatedCapture());
    assertFalse(s.effectiveAdaptiveBuffer());
    var standard = low.withShortMixerPeriod(false).asManual();
    assertEquals(new PhoneTiming(21, 800), standard.phoneTiming());
  }

  @Test
  public void presetsAreExclusiveAndDoNotEnableShortPackets() {
    var base =
        new StreamSettings(true, 19, 4, 87).withProfile(3).withPhoneTiming(new PhoneTiming(8, 350));
    var low = base.preset(1);
    assertEquals(1, low.profile());
    assertFalse(low.manualMode());
    assertTrue(low.effectiveDedicatedCapture());
    assertTrue(low.effectiveShortMixerPeriod());
    assertFalse(low.effectiveShortAudioPackets());
    assertFalse(low.effectiveAdaptiveBuffer());
    assertEquals(0, low.effectiveBufferMs());
    assertEquals(50, low.requestedPresentationMs());
    var balanced = base.preset(0);
    assertEquals(0, balanced.profile());
    assertEquals(150, balanced.effectiveBufferMs());
    assertEquals(1500, balanced.requestedPresentationMs());
    assertFalse(balanced.effectiveDedicatedCapture());
    var quality = base.preset(2);
    assertEquals(2, quality.profile());
    assertEquals(1500, quality.requestedPresentationMs());
    assertFalse(quality.effectiveShortMixerPeriod());
    assertFalse(quality.compressedAudio());
  }

  @Test
  public void phoneBoundsAndQuantizationAreExplicit() {
    assertEquals(48, new PhoneTiming(1, 48).periodFrames());
    assertEquals(96, new PhoneTiming(2, 96).periodFrames());
    assertEquals(928, new PhoneTiming(21, 800).periodFrames());
    assertEquals(96, PhoneTiming.fastest().latencyWindowFrames());
    int previous = 0;
    for (int i = 1; i <= 21; i++) {
      var t = new PhoneTiming(i, 400);
      assertTrue(t.periodFrames() > previous);
      previous = t.periodFrames();
      assertEquals(0, t.periodFrames() % 16);
    }
    assertEquals(2, PhoneTiming.nearest(2.18, 96).periodMs());
    assertEquals(1, PhoneTiming.nearest(1.09, 48).periodMs());
    assertEquals(21, PhoneTiming.nearest(21.04, 800).periodMs());
    assertEquals(PhoneTiming.fastest(), PhoneTiming.fromStored(-1, -1));
    assertEquals(
        new PhoneTiming(21, 800), PhoneTiming.fromStored(Integer.MAX_VALUE, Integer.MAX_VALUE));
    for (double x : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 1.08, 21.05})
      assertThrows(IllegalArgumentException.class, () -> PhoneTiming.nearest(x, 96));
    assertThrows(IllegalArgumentException.class, () -> new PhoneTiming(0, 48));
    assertThrows(IllegalArgumentException.class, () -> new PhoneTiming(22, 96));
    assertThrows(IllegalArgumentException.class, () -> new PhoneTiming(1, 47));
    assertThrows(IllegalArgumentException.class, () -> new PhoneTiming(2, 801));
  }
}
