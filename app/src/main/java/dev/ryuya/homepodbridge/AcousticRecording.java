package dev.ryuya.homepodbridge;

import java.util.Arrays;

/** Bounded microphone storage: clearing atomically prevents further writes and export. */
final class AcousticRecording {
  private final short[] samples;
  private int count;
  private boolean finished, cleared;

  AcousticRecording(int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException();
    samples = new short[capacity];
  }

  synchronized int count() {
    return count;
  }

  int capacity() {
    return samples.length;
  }

  synchronized boolean append(short[] source, int length) {
    if (cleared || finished) return false;
    if (length < 0 || length > source.length || length > samples.length - count)
      throw new IllegalArgumentException();
    System.arraycopy(source, 0, samples, count, length);
    count += length;
    return true;
  }

  synchronized void finish() {
    finished = true;
  }

  synchronized short[] snapshot() {
    return finished && !cleared ? Arrays.copyOf(samples, count) : null;
  }

  synchronized void clear() {
    cleared = true;
    Arrays.fill(samples, (short) 0);
    count = 0;
  }
}
