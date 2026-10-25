package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import org.junit.Test;

public final class PcmPipeTest {
  @Test
  public void fragmentedReadsPreserveStereoFramesAndEndian() throws Exception {
    byte[] raw = {0, (byte) 128, (byte) 255, 127, 1, 0, (byte) 255, (byte) 255};
    var input =
        new ByteArrayInputStream(raw) {
          @Override
          public synchronized int read(byte[] bytes, int offset, int length) {
            return super.read(bytes, offset, Math.min(length, 3));
          }
        };
    PcmPipe pipe = new PcmPipe(Channels.newChannel(input));
    ByteBuffer pcm = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(8, pipe.read(pcm));
    assertTrue(pcm.isDirect());
    short[] samples = new short[4];
    pcm.asShortBuffer().get(samples);
    assertArrayEquals(new short[] {Short.MIN_VALUE, Short.MAX_VALUE, 1, -1}, samples);
    assertEquals(-1, pipe.read(pcm));
  }

  @Test(expected = IOException.class)
  public void partialFrameAtEofIsRejected() throws Exception {
    new PcmPipe(Channels.newChannel(new ByteArrayInputStream(new byte[] {1, 2, 3})))
        .read(ByteBuffer.allocateDirect(8));
  }

  @Test
  public void bufferIsReusedAndFinalWholeFramesStayAligned() throws Exception {
    var pipe = new PcmPipe(Channels.newChannel(new ByteArrayInputStream(new byte[12])));
    ByteBuffer pcm = ByteBuffer.allocateDirect(8);
    assertEquals(8, pipe.read(pcm));
    assertEquals(4, pipe.read(pcm));
    assertEquals(0, pcm.position());
    assertEquals(4, pcm.limit());
    assertEquals(-1, pipe.read(pcm));
  }

  private static byte[] timed(int magic, int length, long stamp, byte[] pcm) {
    return ByteBuffer.allocate(16 + pcm.length)
        .putInt(magic)
        .putInt(length)
        .putLong(stamp)
        .put(pcm)
        .array();
  }

  @Test
  public void timedFragmentsKeepHeaderOutOfPcmAndMeasureMatchingBlock() throws Exception {
    byte[] pcm = {0, (byte) 128, (byte) 255, 127, 1, 0, (byte) 255, (byte) 255};
    var input =
        new ByteArrayInputStream(timed(PcmPipe.MAGIC, 8, 1_000_000, pcm)) {
          @Override
          public synchronized int read(byte[] out, int offset, int length) {
            return super.read(out, offset, Math.min(3, length));
          }
        };
    PcmPipe pipe = new PcmPipe(Channels.newChannel(input), true, () -> 1_234_000L);
    ByteBuffer buffer = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(8, pipe.read(buffer));
    byte[] actual = new byte[8];
    buffer.get(actual);
    assertArrayEquals(pcm, actual);
    assertEquals(234, pipe.transitMicros());
    assertEquals(-1, pipe.read(buffer));
  }

  @Test
  public void malformedTimingAndPartialPayloadFailClosed() throws Exception {
    byte[][] invalid = {
      new byte[] {1, 2, 3},
      timed(0, 4, 1, new byte[4]),
      timed(PcmPipe.MAGIC, 3, 1, new byte[3]),
      timed(PcmPipe.MAGIC, 20, 1, new byte[20]),
      timed(PcmPipe.MAGIC, 0, 1, new byte[0]),
      timed(PcmPipe.MAGIC, 8, 1, new byte[4])
    };
    for (byte[] raw : invalid) {
      try {
        new PcmPipe(Channels.newChannel(new ByteArrayInputStream(raw)), true)
            .read(ByteBuffer.allocateDirect(16));
        fail("Malformed timed block accepted");
      } catch (IOException expected) {
      }
    }
  }

  @Test
  public void futureTimestampIsUnknownRatherThanNegativeLatency() throws Exception {
    PcmPipe pipe =
        new PcmPipe(
            Channels.newChannel(
                new ByteArrayInputStream(timed(PcmPipe.MAGIC, 4, 10_000, new byte[4]))),
            true,
            () -> 9_000);
    assertEquals(4, pipe.read(ByteBuffer.allocateDirect(4)));
    assertEquals(-1, pipe.transitMicros());
  }
}
