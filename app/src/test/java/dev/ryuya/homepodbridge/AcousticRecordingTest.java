package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class AcousticRecordingTest {
  @Test
  public void onlyFinishedBoundedCopiesCanBeExported() {
    var data = new AcousticRecording(4);
    assertNull(data.snapshot());
    assertTrue(data.append(new short[] {1, 2}, 2));
    assertThrows(IllegalArgumentException.class, () -> data.append(new short[] {3, 4, 5}, 3));
    assertTrue(data.append(new short[] {3, 4}, 2));
    data.finish();
    assertFalse(data.append(new short[] {5}, 1));
    var copy = data.snapshot();
    assertArrayEquals(new short[] {1, 2, 3, 4}, copy);
    copy[0] = 99;
    assertEquals(1, data.snapshot()[0]);
  }

  @Test
  public void clearBeforeFinishPreventsLaterWritesAndExport() {
    var data = new AcousticRecording(4);
    data.append(new short[] {1, 2}, 2);
    data.clear();
    assertFalse(data.append(new short[] {3}, 1));
    data.finish();
    assertNull(data.snapshot());
    assertEquals(0, data.count());
    data.clear();
  }

  @Test
  public void clearAfterFinishRevokesFutureExports() {
    var data = new AcousticRecording(4);
    data.append(new short[] {1, 2}, 2);
    data.finish();
    assertNotNull(data.snapshot());
    data.clear();
    assertNull(data.snapshot());
    assertEquals(0, data.count());
  }
}
