package dev.ryuya.homepodbridge.halprobe;

import dev.ryuya.homepodbridge.SilentProbeSource;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Diagnostic-only source, optionally stopping and recreating fixed-zero playback. */
final class ZeroPlayback implements AutoCloseable {
  private final java.util.List<SilentProbeSource> sources = new ArrayList<>();
  private final java.util.concurrent.ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor();
  private final int periodFrames;
  private SilentProbeSource current;
  private boolean closed;

  ZeroPlayback(boolean pulse) {
    this(pulse, 928);
  }

  ZeroPlayback(boolean pulse, int periodFrames) {
    this.periodFrames = periodFrames;
    toggle();
    if (pulse) timer.scheduleAtFixedRate(this::toggle, 3, 3, TimeUnit.SECONDS);
  }

  private synchronized void toggle() {
    if (closed) return;
    if (current != null) {
      current.close();
      current = null;
    } else {
      current = new SilentProbeSource(periodFrames);
      sources.add(current);
      current.start();
    }
  }

  synchronized long writtenFrames() {
    return sources.stream().mapToLong(SilentProbeSource::writtenFrames).sum();
  }

  @Override
  public synchronized void close() {
    closed = true;
    timer.shutdownNow();
    if (current != null) current.close();
    current = null;
  }
}
