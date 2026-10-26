package dev.ryuya.homepodbridge;

/** Numeric diagnostics only; no PCM, receiver addresses, credentials or acoustic-delay claim. */
record PipelineTiming(long[] capture, long[] sender, long pipeMicros, long sampledAtNanos) {
  long capture(int index) {
    return capture != null
            && capture.length >= 10
            && capture[0] == 1
            && index >= 0
            && index < capture.length
        ? capture[index]
        : -1;
  }

  long sender(int index) {
    return sender != null && sender.length == 13 ? sender[index] : -1;
  }

  boolean captureAgeFresh() {
    long at = capture(2);
    return at > 0 && sampledAtNanos >= at && sampledAtNanos - at <= 3_000_000_000L;
  }
}
