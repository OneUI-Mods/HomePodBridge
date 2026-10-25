package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class VolumePolicyTest {
  @Test
  public void explicitMaximumIsHonoured() {
    assertEquals(0, VolumePolicy.clamp(-1, 5));
    assertEquals(0, VolumePolicy.clamp(0, 5));
    assertEquals(5, VolumePolicy.clamp(100, 5));
    assertEquals(5, VolumePolicy.clamp(Integer.MAX_VALUE, 5));
  }

  @Test
  public void applicationSupportsTheFullRange() {
    assertEquals(0, VolumePolicy.apply(-1));
    assertEquals(0, VolumePolicy.apply(0));
    assertEquals(5, VolumePolicy.apply(5));
    assertEquals(50, VolumePolicy.apply(50));
    assertEquals(100, VolumePolicy.apply(100));
    assertEquals(100, VolumePolicy.apply(Integer.MAX_VALUE));
  }
}
