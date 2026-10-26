package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import org.junit.Test;

public final class ZeroProbePcmTest {
  @Test
  public void exactPayloadBecomesSilentWithoutMovingBufferCursorOrTouchingTail() {
    ByteBuffer pcm = ByteBuffer.allocateDirect(32);
    for (int i = 0; i < 32; i++) pcm.put(i, (byte) 0x5a);
    pcm.position(3);
    ZeroProbePcm.clear(pcm, 20);
    assertEquals(3, pcm.position());
    assertEquals(32, pcm.limit());
    for (int i = 0; i < 20; i++) assertEquals(0, pcm.get(i));
    for (int i = 20; i < 32; i++) assertEquals((byte) 0x5a, pcm.get(i));
  }

  @Test
  public void incompleteFramesAndOversizedPayloadsAreRejected() {
    for (int bytes : new int[] {0, -4, 3, 17, 20}) {
      ByteBuffer pcm = ByteBuffer.allocateDirect(16);
      try {
        ZeroProbePcm.clear(pcm, bytes);
        fail();
      } catch (IllegalArgumentException expected) {
      }
    }
  }
}
