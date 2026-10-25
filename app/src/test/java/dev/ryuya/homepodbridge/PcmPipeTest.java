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
}
