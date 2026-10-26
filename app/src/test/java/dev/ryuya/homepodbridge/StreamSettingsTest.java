package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class StreamSettingsTest {
  @org.junit.Test
  public void dedicatedPreferenceSurvivesProfileAndSourceChanges() {
    var value = new StreamSettings(false, 0, 2, 50, true).withDedicatedCapture(true);
    org.junit.Assert.assertTrue(value.effectiveDedicatedCapture());
    org.junit.Assert.assertTrue(
        value
            .withWholeDevice(true)
            .withCompression(true)
            .withDeviceBuffer(true)
            .withDeviceBufferFrames(928)
            .withAdaptiveBuffer(false)
            .dedicatedCapture());
    org.junit.Assert.assertTrue(value.withProfile(2).dedicatedCapture());
    org.junit.Assert.assertFalse(value.withProfile(2).effectiveDedicatedCapture());
    org.junit.Assert.assertTrue(value.withBuffers(0, 2, 50).dedicatedCapture());
  }

  @Test
  public void smallerReserveRemainsOptionalAndSurvivesOtherSettings() {
    var balanced = new StreamSettings(true, 0, 2, 50, true).withDeviceBuffer(true);
    assertEquals(1856, balanced.deviceBufferFrames());
    var faster = balanced.withDeviceBufferFrames(928);
    assertEquals(
        928,
        faster
            .withCompression(true)
            .withAdaptiveBuffer(false)
            .withWholeDevice(false)
            .withProfile(2)
            .withProfile(1)
            .deviceBufferFrames());
    assertFalse(faster.withProfile(2).effectiveDeviceBuffer());
    assertEquals(928, faster.withBuffers(0, 2, 50).deviceBufferFrames());
    assertEquals(928, faster.withDeviceBuffer(false).deviceBufferFrames());
    assertEquals(1856, faster.withDeviceBufferFrames(464).deviceBufferFrames());
    assertEquals(1856, faster.withDeviceBufferFrames(0).deviceBufferFrames());
    assertEquals(50, faster.effectiveLatencyMs());
  }

  @Test
  public void deviceBufferIsOptInAndOnlyEffectiveInLowLatency() {
    var custom = new StreamSettings(true, 70, 20, 320);
    assertFalse(custom.deviceBuffer());
    var enabled = custom.withDeviceBuffer(true);
    assertFalse(enabled.effectiveDeviceBuffer());
    assertTrue(enabled.withProfile(1).effectiveDeviceBuffer());
    assertFalse(enabled.withProfile(2).effectiveDeviceBuffer());
    var changed =
        enabled
            .withProfile(1)
            .withAdaptiveBuffer(false)
            .withCompression(true)
            .withWholeDevice(false);
    assertTrue(changed.deviceBuffer());
    assertFalse(changed.withDeviceBuffer(false).effectiveDeviceBuffer());
    assertTrue(changed.withBuffers(0, 2, 50).deviceBuffer());
    assertEquals(enabled, enabled.withProfile(1).withProfile(0));
  }

  @Test
  public void unsafePreferencesAreBounded() {
    var low = new StreamSettings(false, -1, -1, -1);
    assertEquals(0, low.bufferMs());
    assertEquals(2, low.blockMs());
    assertEquals(10, low.latencyMs());
    var high = new StreamSettings(true, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    assertEquals(300, high.bufferMs());
    assertEquals(40, high.blockMs());
    assertEquals(2000, high.latencyMs());
    assertTrue(high.wholeDevice());
  }

  @Test
  public void everyBlockContainsWholeStereoFrames() {
    for (int ms = 2; ms <= 40; ms++) {
      int samples = new StreamSettings(false, 150, ms, 1500).samplesPerRead();
      assertEquals(0, samples % 2);
      assertTrue(samples > 0);
      assertEquals(44100 * ms / 1000, samples / 2);
    }
  }

  @Test
  public void lowLatencyPreservesCustomSettingsForSwitchingBack() {
    var low = new StreamSettings(true, 150, 10, 1500, true);
    assertEquals(8, low.effectiveBufferMs());
    assertTrue(low.effectiveAdaptiveBuffer());
    assertEquals(0, low.withAdaptiveBuffer(false).effectiveBufferMs());
    assertFalse(low.withAdaptiveBuffer(false).effectiveAdaptiveBuffer());
    assertEquals(2, low.effectiveBlockMs());
    assertEquals(50, low.effectiveLatencyMs());
    assertEquals(176, low.samplesPerRead());
    assertEquals(150, low.bufferMs());
    assertEquals(10, low.blockMs());
    assertEquals(1500, low.latencyMs());
  }

  @Test
  public void existingLowLatencyPresetUsesAudibleTargetWhileCustomExperimentsStayExplicit() {
    var existing = new StreamSettings(true, 0, 2, 40, true, false, true, false);
    assertEquals(50, existing.effectiveLatencyMs());
    assertEquals(0, existing.effectiveBufferMs());
    assertEquals(2, existing.effectiveBlockMs());
    assertTrue(existing.compressedAudio());
    var custom = existing.withProfile(0);
    assertEquals(40, custom.effectiveLatencyMs());
    assertEquals(50, custom.withProfile(1).effectiveLatencyMs());
  }

  @Test
  public void profilesAndCompressionAreIndependentAndReversible() {
    var custom = new StreamSettings(true, 70, 20, 320).withCompression(true);
    var quality = custom.withProfile(2);
    assertTrue(quality.highQuality());
    assertFalse(quality.effectiveAdaptiveBuffer());
    assertFalse(quality.lowLatency());
    assertEquals(150, quality.effectiveBufferMs());
    assertEquals(10, quality.effectiveBlockMs());
    assertEquals(1500, quality.effectiveLatencyMs());
    var low = quality.withProfile(1);
    assertTrue(low.lowLatency());
    assertFalse(low.highQuality());
    assertTrue(low.compressedAudio());
    assertFalse(low.withCompression(false).compressedAudio());
    assertTrue(low.withCompression(false).lowLatency());
    assertEquals(custom, low.withProfile(0));
    assertTrue(quality.withWholeDevice(false).highQuality());
    assertTrue(quality.withWholeDevice(false).compressedAudio());
    var tuned = quality.withBuffers(0, 5, 20);
    assertFalse(tuned.lowLatency());
    assertFalse(tuned.highQuality());
    assertTrue(tuned.compressedAudio());
    assertEquals(20, tuned.effectiveLatencyMs());
  }
}
