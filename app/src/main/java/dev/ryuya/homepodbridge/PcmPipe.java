package dev.ryuya.homepodbridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/** Reads framed stereo PCM despite arbitrary pipe read boundaries. */
final class PcmPipe {
  private final ReadableByteChannel input;

  PcmPipe(ReadableByteChannel input) {
    this.input = input;
  }

  int read(ByteBuffer pcm) throws IOException {
    pcm.clear();
    while (pcm.hasRemaining()) {
      int read = input.read(pcm);
      if (read < 0) break;
      if (read == 0) continue;
    }
    int count = pcm.position();
    if (count % 4 != 0) throw new IOException("Truncated stereo frame");
    pcm.flip();
    return count == 0 ? -1 : count;
  }
}
