package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class BufferLimitsTest {
  @Test
  public void customSettingsUseTheSameBoundsAsTheControls() {
    var low = new StreamSettings(false, -1, -1, -1);
    assertEquals(BufferLimits.RESERVE_MIN_MS, low.bufferMs());
    assertEquals(BufferLimits.CAPTURE_MIN_MS, low.blockMs());
    assertEquals(BufferLimits.TIMING_MIN_MS, low.latencyMs());
    var high = new StreamSettings(false, 9999, 9999, 9999);
    assertEquals(BufferLimits.RESERVE_MAX_MS, high.bufferMs());
    assertEquals(BufferLimits.CAPTURE_MAX_MS, high.blockMs());
    assertEquals(BufferLimits.TIMING_MAX_MS, high.latencyMs());
    assertEquals(BufferLimits.TIMING_MIN_MS, new ReceiverTiming(true, -1, -1, -1).presentationMs());
  }

  @Test
  public void dedicatedUnitIsFixedAndCustomRequiresAnExplicitProfileChange() {
    var fast = new StreamSettings(true, 0, 2, 50, true).withDedicatedCapture(true);
    assertEquals(22 * 1000.0 / 44100, fast.captureUnitMs(), 0.00001);
    assertEquals(1, fast.profile());
    var custom = fast.withBuffers(0, 2, 50);
    assertEquals(0, custom.profile());
    assertFalse(custom.effectiveDedicatedCapture());
    assertEquals(2, custom.captureUnitMs(), 0.00001);
    assertTrue(custom.dedicatedCapture());
  }
}
