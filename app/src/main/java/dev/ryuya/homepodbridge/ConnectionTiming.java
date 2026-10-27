package dev.ryuya.homepodbridge;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Local connection stages may overlap; never sum them into a fabricated total. */
final class ConnectionTiming {
  enum Stage {
    SELECTED,
    WORKER,
    NETWORK,
    CAPTURE,
    SENDER,
    AUTHENTICATED,
    READY
  }

  private final long[] times = new long[Stage.values().length];
  private final LongSupplier clock;

  ConnectionTiming() {
    this(System::nanoTime);
  }

  ConnectionTiming(LongSupplier clock) {
    this.clock = clock;
    Arrays.fill(times, -1);
    mark(Stage.SELECTED);
  }

  synchronized void mark(Stage stage) {
    markAt(stage, clock.getAsLong());
  }

  synchronized void markAt(Stage stage, long at) {
    if (times[stage.ordinal()] < 0 && at > 0 && (times[0] < 0 || at >= times[0]))
      times[stage.ordinal()] = at;
  }

  synchronized long micros(Stage from, Stage to) {
    long a = times[from.ordinal()], b = times[to.ordinal()];
    return a < 0 || b < a ? -1 : (b - a) / 1000;
  }

  synchronized String summary() {
    return "connectionTimingMs queue="
        + ms(Stage.SELECTED, Stage.WORKER)
        + " network="
        + ms(Stage.WORKER, Stage.NETWORK)
        + " capture="
        + ms(Stage.NETWORK, Stage.CAPTURE)
        + " handshake="
        + ms(Stage.SENDER, Stage.AUTHENTICATED)
        + " total="
        + ms(Stage.SELECTED, Stage.READY);
  }

  private long ms(Stage from, Stage to) {
    long value = micros(from, to);
    return value < 0 ? -1 : value / 1000;
  }
}
