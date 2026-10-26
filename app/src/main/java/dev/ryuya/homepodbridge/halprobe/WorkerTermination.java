package dev.ryuya.homepodbridge.halprobe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Android Process.destroyForcibly can close its pipes: collect available evidence first. */
final class WorkerTermination {
  record Outcome(int exitCode, boolean forced, boolean timedOut, String diagnostics) {}

  interface Killer {
    void kill() throws Exception;
  }

  static Outcome collect(Process child, long readWaitNanos, Killer killer) throws Exception {
    boolean forced = !child.waitFor(1, TimeUnit.SECONDS);
    String diagnostics = "";
    try {
      diagnostics = available(child.getInputStream()) + available(child.getErrorStream());
    } catch (Exception unavailable) {
      diagnostics = "WORKER_DIAGNOSTICS_UNAVAILABLE";
    } finally {
      if (forced) killer.kill();
    }
    if (!child.waitFor(1, TimeUnit.SECONDS))
      throw new IOException("Worker termination unconfirmed");
    return new Outcome(
        child.exitValue(),
        forced,
        forced && readWaitNanos >= TimeUnit.SECONDS.toNanos(3),
        diagnostics);
  }

  private static String available(InputStream stream) throws IOException {
    int count = Math.min(1024, stream.available());
    return count == 0 ? "" : new String(stream.readNBytes(count), StandardCharsets.UTF_8);
  }

  private WorkerTermination() {}
}
