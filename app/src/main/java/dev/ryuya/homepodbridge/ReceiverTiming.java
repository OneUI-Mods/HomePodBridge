package dev.ryuya.homepodbridge;

/** Independent negotiation and RTP presentation settings; neither value is measured latency. */
record ReceiverTiming(boolean independent, int minimumMs, int maximumMs, int presentationMs) {
  ReceiverTiming {
    minimumMs = clamp(minimumMs);
    maximumMs = Math.max(minimumMs, clamp(maximumMs));
    presentationMs = clamp(presentationMs);
  }

  static ReceiverTiming linked(int targetMs) {
    return new ReceiverTiming(false, Math.min(targetMs, 250), targetMs, targetMs);
  }

  private static int clamp(int value) {
    return BufferLimits.clamp(value, BufferLimits.TIMING_MIN_MS, BufferLimits.TIMING_MAX_MS);
  }
}
