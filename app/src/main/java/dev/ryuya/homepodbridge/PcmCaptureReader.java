package dev.ryuya.homepodbridge;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Drain available complete stereo frames; block for only one frame when the source is empty. */
final class PcmCaptureReader {
  interface Reader {
    int read(ByteBuffer buffer, int bytes, int mode);
  }

  final ByteBuffer buffer;
  private final ByteBuffer afterFirstFrame;
  private final Reader reader;
  private final boolean drain;

  PcmCaptureReader(int blockBytes, boolean drain, Reader reader) {
    if (blockBytes < 4 || blockBytes % 4 != 0)
      throw new IllegalArgumentException("Stereo block required");
    buffer = ByteBuffer.allocateDirect(blockBytes);
    buffer.position(4);
    afterFirstFrame = buffer.slice();
    buffer.clear();
    this.reader = reader;
    this.drain = drain;
  }

  int read() throws IOException {
    buffer.clear();
    int count = reader.read(buffer, buffer.capacity(), drain ? 1 : 0);
    check(count, buffer.capacity());
    if (drain && count == 0) {
      count = reader.read(buffer, 4, 0);
      check(count, 4);
      if (count > 0 && afterFirstFrame.capacity() > 0) {
        int more = reader.read(afterFirstFrame, afterFirstFrame.capacity(), 1);
        check(more, afterFirstFrame.capacity());
        if (more < 0) return more;
        count += more;
      }
    }
    buffer.position(0).limit(Math.max(0, count));
    return count;
  }

  private static void check(int count, int maximum) throws IOException {
    if (count > maximum || (count > 0 && count % 4 != 0))
      throw new IOException("Invalid stereo capture read");
  }
}
