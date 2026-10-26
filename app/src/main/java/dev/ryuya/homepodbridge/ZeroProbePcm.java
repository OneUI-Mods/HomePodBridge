package dev.ryuya.homepodbridge;

import java.nio.ByteBuffer;

/**
 * Explicit diagnostic path only: retain payload length/timing and send exclusively zero samples.
 */
final class ZeroProbePcm {
  static void clear(ByteBuffer pcm, int bytes) {
    if (bytes <= 0 || bytes % 4 != 0 || bytes > pcm.capacity())
      throw new IllegalArgumentException("Invalid stereo payload");
    for (int i = 0; i < bytes; i += 4) pcm.putInt(i, 0);
  }

  private ZeroProbePcm() {}
}
