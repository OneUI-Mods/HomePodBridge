package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class StreamSettingsTest {
  @Test
  public void unsafePreferencesAreBounded() {
    var low = new StreamSettings(false, -1, -1, -1);
    assertEquals(0, low.bufferMs());
    assertEquals(5, low.blockMs());
    assertEquals(250, low.latencyMs());
    var high = new StreamSettings(true, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    assertEquals(300, high.bufferMs());
    assertEquals(40, high.blockMs());
    assertEquals(2000, high.latencyMs());
    assertTrue(high.wholeDevice());
  }

  @Test
  public void everyBlockContainsWholeStereoFrames() {
    for (int ms = 5; ms <= 40; ms++) {
      int samples = new StreamSettings(false, 150, ms, 1500).samplesPerRead();
      assertEquals(0, samples % 2);
      assertTrue(samples > 0);
      assertEquals(44100 * ms / 1000, samples / 2);
    }
  }
}
