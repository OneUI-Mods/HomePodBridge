package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import org.junit.Test;

public final class PcmCaptureReaderTest {
  @Test
  public void residualHalFramesAreReturnedWithoutWaitingForNextBurst() throws Exception {
    byte[] original = new byte[928 * 4 * 3];
    for (int i = 0; i < original.length; i++) original[i] = (byte) (i * 37);
    int[] position = {0};
    int[] available = {0};
    int[] bursts = {0};
    var capture =
        new PcmCaptureReader(
            88 * 4,
            true,
            (buffer, bytes, mode) -> {
              if (available[0] == 0 && mode == 0) {
                assertEquals(4, bytes);
                if (position[0] == original.length) return -1;
                available[0] = 928 * 4;
                bursts[0]++;
              }
              int take = Math.min(bytes, available[0]);
              for (int i = 0; i < take; i++) buffer.put(i, original[position[0] + i]);
              available[0] -= take;
              position[0] += take;
              return take;
            });
    var out = new ByteArrayOutputStream();
    for (int burst = 1; burst <= 3; burst++) {
      for (int i = 0; i < 10; i++) {
        assertEquals(352, capture.read());
        while (capture.buffer.hasRemaining()) out.write(capture.buffer.get());
      }
      assertEquals(192, capture.read());
      assertEquals(burst, bursts[0]); // The 48-frame remainder did not wait for another HAL burst.
      while (capture.buffer.hasRemaining()) out.write(capture.buffer.get());
    }
    assertArrayEquals(original, out.toByteArray());
    assertEquals(-1, capture.read());
  }

  @Test
  public void legacyReaderStillRequestsWholeBlocks() throws Exception {
    var capture =
        new PcmCaptureReader(
            352,
            false,
            (buffer, size, mode) -> {
              assertEquals(352, size);
              assertEquals(0, mode);
              return 352;
            });
    assertEquals(352, capture.read());
    assertTrue(capture.buffer.isDirect());
  }

  @Test(expected = java.io.IOException.class)
  public void nonStereoReadCannotReachThePipe() throws Exception {
    new PcmCaptureReader(352, true, (buffer, size, mode) -> 3).read();
  }
}
