package dev.ryuya.homepodbridge;

/** Validated application limits shared by stored settings and controls, not acoustic guarantees. */
final class BufferLimits {
  static final int RESERVE_MIN_MS = 0, RESERVE_MAX_MS = 300;
  static final int CAPTURE_MIN_MS = 2, CAPTURE_MAX_MS = 40;
  static final int TIMING_MIN_MS = 10, TIMING_MAX_MS = 2000;
  static final int DEDICATED_FRAMES = 22;

  static int clamp(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private BufferLimits() {}
}
