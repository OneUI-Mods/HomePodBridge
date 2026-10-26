package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ReceiverTimingTest {
  @Test
  public void existingProfilesKeepTheirLinkedTargets() {
    var low = new StreamSettings(true, 0, 2, 40, true);
    assertEquals(50, low.requestedPresentationMs());
    assertEquals(50, low.requestedReceiverMinimumMs());
    assertEquals(50, low.requestedReceiverMaximumMs());
    var quality = low.withProfile(2);
    assertEquals(1500, quality.requestedPresentationMs());
    assertEquals(250, quality.requestedReceiverMinimumMs());
    assertEquals(1500, quality.requestedReceiverMaximumMs());
  }

  @Test
  public void independentTimingDoesNotDisableDedicatedCaptureOrChangeItsOtherSettings() {
    var original = new StreamSettings(true, 0, 2, 50, true).withDedicatedCapture(true);
    var split = original.withReceiverTiming(new ReceiverTiming(true, 50, 80, 20));
    assertTrue(split.effectiveDedicatedCapture());
    assertEquals(original.effectiveBufferMs(), split.effectiveBufferMs());
    assertEquals(50, split.requestedReceiverMinimumMs());
    assertEquals(80, split.requestedReceiverMaximumMs());
    assertEquals(20, split.requestedPresentationMs());
    assertEquals(
        split.receiverTiming(),
        split
            .withWholeDevice(false)
            .withCompression(true)
            .withAdaptiveBuffer(false)
            .withDeviceBuffer(true)
            .withDeviceBufferFrames(928)
            .withDedicatedCapture(false)
            .withBuffers(0, 2, 40)
            .withProfile(2)
            .receiverTiming());
    assertEquals(20, split.withProfile(2).requestedPresentationMs());
  }

  @Test
  public void disablingIndependentTimingRestoresTheCurrentProfile() {
    var split =
        new StreamSettings(false, 0, 2, 40, true)
            .withReceiverTiming(new ReceiverTiming(true, 50, 2000, 10));
    var linked = split.withReceiverTiming(new ReceiverTiming(false, 50, 2000, 10));
    assertEquals(50, linked.requestedPresentationMs());
    assertEquals(50, linked.requestedReceiverMaximumMs());
    assertEquals(40, linked.withProfile(0).requestedPresentationMs());
  }

  @Test
  public void corruptedStoredTimingCannotInvertOrOverflowTheWindow() {
    assertEquals(
        new ReceiverTiming(true, 10, 2000, 2000),
        new ReceiverTiming(true, -1, Integer.MAX_VALUE, Integer.MAX_VALUE));
    assertEquals(2000, new ReceiverTiming(true, 2000, 10, 20).maximumMs());
  }
}
