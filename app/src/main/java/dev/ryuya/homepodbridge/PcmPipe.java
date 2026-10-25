package dev.ryuya.homepodbridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/** Reads framed stereo PCM despite arbitrary pipe read boundaries. */
final class PcmPipe {
  private final ReadableByteChannel input;
  static final int MAGIC = 0x48425031;
  private final boolean timed;
  private final ByteBuffer header = ByteBuffer.allocateDirect(16);
  private final java.util.function.LongSupplier clock;
  private long transitMicros = -1;

  PcmPipe(ReadableByteChannel input) {
    this(input, false, System::nanoTime);
  }

  PcmPipe(ReadableByteChannel input, boolean timed) {
    this(input, timed, System::nanoTime);
  }

  PcmPipe(ReadableByteChannel input, boolean timed, java.util.function.LongSupplier clock) {
    this.input = input;
    this.timed = timed;
    this.clock = clock;
  }

  long transitMicros() {
    return transitMicros;
  }

  int read(ByteBuffer pcm) throws IOException {
    pcm.clear();
    long capturedAt = 0;
    if (timed) {
      header.clear();
      if (fill(header) == 0) return -1;
      if (header.position() != 16) throw new IOException("Truncated timing header");
      header.flip();
      if (header.getInt() != MAGIC) throw new IOException("Invalid timing header");
      int length = header.getInt();
      capturedAt = header.getLong();
      if (length <= 0 || length > pcm.capacity() || length % 4 != 0)
        throw new IOException("Invalid PCM block length");
      pcm.limit(length);
    }
    int expected = pcm.remaining();
    int count = fill(pcm);
    if (timed && count != expected) throw new IOException("Truncated timed PCM block");
    if (count % 4 != 0) throw new IOException("Truncated stereo frame");
    if (timed) {
      long elapsed = clock.getAsLong() - capturedAt;
      transitMicros = capturedAt > 0 && elapsed >= 0 ? elapsed / 1000 : -1;
    }
    pcm.flip();
    return count == 0 ? -1 : count;
  }

  private int fill(ByteBuffer buffer) throws IOException {
    int start = buffer.position();
    while (buffer.hasRemaining()) {
      int read = input.read(buffer);
      if (read < 0) break;
      if (read == 0) continue;
    }
    return buffer.position() - start;
  }
}
