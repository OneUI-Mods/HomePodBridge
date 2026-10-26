package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class PipelineTimingTest {
  @Test
  public void appendedProbeMetricsKeepTheExistingTimingPrefix() {
    long[] capture = {1, 61000, 1_000_000_000L, 2000, 21000, 20, 50, 2784, 88, 1, 44100};
    var timing = new PipelineTiming(capture, null, 100, 2_000_000_000L);
    assertEquals(61000, timing.capture(1));
    assertEquals(1, timing.capture(9));
    assertTrue(timing.captureAgeFresh());
    assertEquals(-1, timing.capture(-1));
    assertEquals(-1, timing.capture(11));
  }

  @Test
  public void missingOrOldSchemaNeverBecomesZeroDelay() {
    var empty = new PipelineTiming(null, null, -1, 1);
    assertEquals(-1, empty.capture(1));
    assertEquals(-1, empty.sender(0));
    assertFalse(empty.captureAgeFresh());
    long[] old = new long[10];
    old[0] = 2;
    assertEquals(-1, new PipelineTiming(old, new long[4], -1, 1).capture(1));
  }

  @Test
  public void staleAndFutureCaptureClocksAreNotLiveMeasurements() {
    long[] capture = {1, 82000, 1_000_000_000L, 0, 0, 0, 0, 2784, 88, 0};
    assertTrue(new PipelineTiming(capture, null, 100, 2_000_000_000L).captureAgeFresh());
    assertFalse(new PipelineTiming(capture, null, 100, 5_000_000_001L).captureAgeFresh());
    assertFalse(new PipelineTiming(capture, null, 100, 500_000_000L).captureAgeFresh());
  }
}
