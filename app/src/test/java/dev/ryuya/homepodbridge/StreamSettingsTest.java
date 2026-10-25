package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class StreamSettingsTest {
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
