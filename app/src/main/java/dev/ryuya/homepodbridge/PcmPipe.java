package dev.ryuya.homepodbridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/** Reads framed stereo PCM despite arbitrary pipe read boundaries. */
final class PcmPipe {
  private final ReadableByteChannel input;
  static final int MAGIC = 0x48425031;
  static final int HAL_ZERO_MAGIC = 0x48425032;
  static final int HAL_MAGIC = 0x48425033;
  private final boolean timed;
  private final boolean halZero;
  private final int halMagic;
  private final ByteBuffer header;
  private final java.util.function.LongSupplier clock;
  private long transitMicros = -1;
  private volatile long pendingFrames = -1;

  PcmPipe(ReadableByteChannel input) {
    this(input, false, System::nanoTime);
  }

  PcmPipe(ReadableByteChannel input, boolean timed) {
    this(input, timed, System::nanoTime);
  }

  PcmPipe(ReadableByteChannel input, boolean timed, java.util.function.LongSupplier clock) {
    this(input, timed, clock, false);
  }

  PcmPipe(
      ReadableByteChannel input,
      boolean timed,
      java.util.function.LongSupplier clock,
      boolean halZero) {
    this(input, timed, clock, halZero ? HAL_ZERO_MAGIC : 0);
  }

  PcmPipe(
      ReadableByteChannel input, boolean timed, java.util.function.LongSupplier clock, int magic) {
    boolean halZero = magic != 0;
    if (magic != 0 && magic != HAL_MAGIC && magic != HAL_ZERO_MAGIC)
      throw new IllegalArgumentException();
    if (halZero && !timed) throw new IllegalArgumentException("HAL records require timing");
    this.input = input;
    this.timed = timed;
    this.clock = clock;
    this.halZero = halZero;
    this.halMagic = magic;
    header = ByteBuffer.allocateDirect(halZero ? 24 : 16);
  }

  long pendingFrames() {
    return pendingFrames;
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
      if (header.position() != header.capacity()) throw new IOException("Truncated timing header");
      header.flip();
      if (header.getInt() != (halZero ? halMagic : MAGIC))
        throw new IOException("Invalid timing header");
      int length = header.getInt();
      capturedAt = header.getLong();
      if (halZero) {
        long pending = header.getLong();
        if (pending < (halMagic == HAL_MAGIC ? -1 : 0) || pending > 4096)
          throw new IOException("Invalid HAL queue depth");
        pendingFrames = pending;
      }
      if (length <= 0 || length > pcm.capacity() || length % 4 != 0)
        throw new IOException("Invalid PCM block length");
      if (halZero && (length != 176 || capturedAt <= 0))
        throw new IOException("Invalid zero HAL record");
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
