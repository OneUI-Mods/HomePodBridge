package dev.ryuya.homepodbridge.halprobe;

import static org.junit.Assert.*;

import org.junit.Test;

public final class CaptureScopeTest {
  @Test
  public void selectedScopeMatchesOnlyTheResolvedUidAndMediaUsage() {
    var scope = CaptureScope.parse("selected", 12345, 10234);
    assertFalse(scope.whole());
    assertEquals(12345, scope.selectedUid());
    assertEquals(1, scope.matchedUsage());
    assertEquals(0, scope.excludedUsages().length);
    assertFalse(scope.zeroOnly());
  }

  @Test
  public void wholeScopeExcludesBridgeAndBothCallUsages() {
    var scope = CaptureScope.parse("whole", 12345, 10234);
    assertTrue(scope.whole());
    assertEquals(10234, scope.excludedUid());
    assertArrayEquals(new int[] {2, 3}, scope.excludedUsages());
    assertEquals(-1, scope.matchedUsage());
  }

  @Test
  public void zeroSourcesCannotWidenTheNormalSelectedScope() {
    var scope = CaptureScope.parse("test-cycle", 12345, 10234);
    assertTrue(scope.zeroOnly());
    assertTrue(scope.pulse());
    assertEquals(0, scope.selectedUid());
    assertFalse(scope.whole());
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsUnrecognizedScope() {
    CaptureScope.parse("microphone", 12345, 10234);
  }
}
