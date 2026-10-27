package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class AcousticSignalTest {
  @Test
  public void fixedChirpsHaveQuietBoundedAmplitudeAndSilentEdges() {
    for (int tone = 0; tone < AcousticSignal.COUNT; tone++) {
      long energy = 0;
      assertEquals(0, AcousticSignal.sample(tone, -1));
      assertEquals(0, AcousticSignal.sample(tone, 0));
      assertEquals(0, AcousticSignal.sample(tone, AcousticSignal.TONE_FRAMES - 1));
      assertEquals(0, AcousticSignal.sample(tone, AcousticSignal.TONE_FRAMES));
      for (int i = 0; i < AcousticSignal.TONE_FRAMES; i++) {
        int s = AcousticSignal.sample(tone, i);
        assertTrue(Math.abs(s) <= 3933);
        energy += (long) s * s;
      }
      assertTrue(energy > 1000000);
    }
    assertEquals(0, AcousticSignal.sample(-1, 100));
    assertEquals(0, AcousticSignal.sample(3, 100));
  }

  @Test
  public void distinctPatternsAndSpacingAvoidAmbiguousSingleToneMatches() {
    for (int tone = 1; tone < 3; tone++) {
      assertTrue(
          AcousticSignal.firstFrame(tone) - AcousticSignal.firstFrame(tone - 1)
              > AcousticSignal.TONE_FRAMES * 5);
      long dot = 0, aa = 0, bb = 0;
      for (int i = 0; i < AcousticSignal.TONE_FRAMES; i++) {
        long a = AcousticSignal.sample(0, i), b = AcousticSignal.sample(tone, i);
        dot += a * b;
        aa += a * a;
        bb += b * b;
      }
      assertTrue(Math.abs(dot) / Math.sqrt((double) aa * bb) < 0.5);
    }
  }
}
