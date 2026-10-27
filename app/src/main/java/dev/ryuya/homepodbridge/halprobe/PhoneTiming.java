package dev.ryuya.homepodbridge.halprobe;

/** Bounded owned-route parameters. Queue measurements are not configuration values. */
public record PhoneTiming(int periodMs, int reserveFrames) {
  public static final int MIN_PERIOD_MS = 1, MAX_PERIOD_MS = 21;
  public static final int MIN_RESERVE = 64, MAX_RESERVE = 800;

  public PhoneTiming {
    if (periodMs < MIN_PERIOD_MS
        || periodMs > MAX_PERIOD_MS
        || reserveFrames < MIN_RESERVE
        || reserveFrames > MAX_RESERVE)
      throw new IllegalArgumentException("Unsupported phone timing");
  }

  public int periodFrames() {
    return ((44100 * periodMs / 1000 + 15) / 16) * 16;
  }

  public int latencyWindowFrames() {
    return periodFrames() * 2;
  }

  public double actualPeriodMs() {
    return periodFrames() * 1000.0 / 44100;
  }

  public double reserveMs() {
    return reserveFrames * 1000.0 / 44100;
  }

  public static PhoneTiming nearest(double actualMs, int reserve) {
    if (!Double.isFinite(actualMs) || actualMs < 1.09 || actualMs > 21.04)
      throw new IllegalArgumentException();
    int best = MIN_PERIOD_MS;
    double distance = Double.POSITIVE_INFINITY;
    for (int ms = MIN_PERIOD_MS; ms <= MAX_PERIOD_MS; ms++) {
      double d = Math.abs(new PhoneTiming(ms, reserve).actualPeriodMs() - actualMs);
      if (d < distance) {
        best = ms;
        distance = d;
      }
    }
    return new PhoneTiming(best, reserve);
  }

  public static PhoneTiming fastest() {
    return new PhoneTiming(1, 64);
  }

  public static PhoneTiming fromStored(int period, int reserve) {
    return new PhoneTiming(
        Math.max(MIN_PERIOD_MS, Math.min(MAX_PERIOD_MS, period)),
        Math.max(MIN_RESERVE, Math.min(MAX_RESERVE, reserve)));
  }
}
