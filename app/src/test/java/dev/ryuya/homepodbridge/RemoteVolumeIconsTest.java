package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class RemoteVolumeIconsTest {
  @Test
  public void onlyOwnedDynamicRowTogglesAndRestoresItsVolume() {
    var icons = new RemoteVolumeIcons();
    assertNull(icons.target(100, 20));
    icons.stream = 103;
    assertNull(icons.target(3, 12));
    assertNull(icons.target(104, 90));
    assertEquals(Integer.valueOf(0), icons.target(103, 2));
    assertEquals(Integer.valueOf(2), icons.target(103, 0));
    assertEquals(Integer.valueOf(0), icons.target(103, 20));
    assertEquals(Integer.valueOf(20), icons.target(103, 0));
    var nextConnection = new RemoteVolumeIcons();
    nextConnection.stream = 105;
    assertEquals(Integer.valueOf(1), nextConnection.target(105, 0));
    assertNull(nextConnection.target(103, 0));
  }
}
