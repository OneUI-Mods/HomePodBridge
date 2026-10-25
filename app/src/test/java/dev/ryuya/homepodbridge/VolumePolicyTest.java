package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class VolumePolicyTest {
  @Test
  public void deviceTestsCannotExceedFivePercent() {
    assertEquals(0, VolumePolicy.clamp(-1, 5));
    assertEquals(0, VolumePolicy.clamp(0, 5));
    assertEquals(5, VolumePolicy.clamp(100, 5));
    assertEquals(5, VolumePolicy.clamp(Integer.MAX_VALUE, 5));
  }

  @Test
  public void normalBuildsSupportTheFullRange() {
    assertEquals(100, VolumePolicy.clamp(100, 100));
    assertEquals(50, VolumePolicy.clamp(50, 100));
    assertEquals(100, VolumePolicy.clamp(101, 100));
  }
}
