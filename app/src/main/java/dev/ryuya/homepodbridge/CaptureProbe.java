package dev.ryuya.homepodbridge;

import android.content.Context;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Bounded diagnostics using the normal owned capture path, without a sender or source playback. */
final class CaptureProbe {
  private static boolean running;
  private static String status = "Not requested";
  private static Runnable cancellation;

  static void cancel() {
    Runnable action;
    synchronized (CaptureProbe.class) {
      action = cancellation;
    }
    if (action != null) new Thread(action, "HomePod-probe-cancel").start();
  }

  static synchronized void request(Context context) {
    request(context, 0);
  }

  static synchronized void request(Context context, boolean freeze) {
    request(context, freeze ? 1 : 0);
  }

  static synchronized void request(Context context, int operation) {
    if (operation < 0 || operation > 2) return;
    if (running) return;
    if (BridgeState.capture != null || !RootConnector.available()) {
      status = "Probe requires idle sharing and an available system bridge";
      return;
    }
    running = true;
    status = "Capture-only probe running; no network sender or playback command";
    Context app = context.getApplicationContext();
    new Thread(() -> run(app, operation), "HomePod-capture-probe").start();
  }

  private static void run(Context context, int operation) {
    Binder lifetime = new Binder();
    ParcelFileDescriptor descriptor = null;
    var timer = Executors.newSingleThreadScheduledExecutor();
    ArrayList<Long> ages = new ArrayList<>();
    long frames = 0;
    long zeroSourceFrames = 0;
    String tuningReport = "", restoreReport = "";
    try {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
      StreamSettings settings = StreamSettings.load(context);
      if (!settings.wholeDevice()) throw new IllegalStateException("Whole-device mode required");
      if (!settings.lowLatency() || settings.effectiveLatencyMs() != 50)
        throw new IllegalStateException("The 50 ms low-latency preset is required for comparison");
      RootConnector.Input input = RootConnector.open(BridgeState.MUSIC, lifetime, settings, true);
      descriptor = input.pipe();
      ParcelFileDescriptor owned = descriptor;
      Runnable close =
          () -> {
            try {
              owned.close();
            } catch (java.io.IOException ignored) {
            }
            RootConnector.close(lifetime);
          };
      synchronized (CaptureProbe.class) {
        cancellation = close;
      }
      timer.schedule(close, 15, TimeUnit.SECONDS);
      long begin = System.nanoTime(), nextTiming = begin + TimeUnit.SECONDS.toNanos(2);
      long lastObservation = 0;
      long sampleAfter = begin + TimeUnit.SECONDS.toNanos(4);
      boolean tuned = false;
      boolean inspected = false;
      try (var stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
        try {
          PcmPipe pipe = new PcmPipe(stream.getChannel(), input.timed());
          ByteBuffer pcm = ByteBuffer.allocateDirect(settings.samplesPerRead() * 2);
          while (System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(11)) {
            int count = pipe.read(pcm);
            if (count < 0) break;
            frames += count / 4;
            long now = System.nanoTime();
            if (now < nextTiming) continue;
            nextTiming = now + TimeUnit.MILLISECONDS.toNanos(250);
            long[] timing = RootConnector.captureTiming(lifetime);
            if (timing != null && timing.length >= 11) zeroSourceFrames = timing[10];
            if (operation == 2 && inspected && !tuned) {
              String report = HalInspection.result();
              if (report != null) {
                tuningReport = report;
                if (!report.contains("Owned reserve changed: 2587 -> 1856"))
                  throw new IllegalStateException("Owned tuning was refused");
                tuned = true;
                sampleAfter = now + TimeUnit.SECONDS.toNanos(2);
              }
            }
            if (timing != null
                && timing.length >= 3
                && timing[0] == 1
                && timing[1] >= 0
                && now >= timing[2]
                && now - timing[2] < TimeUnit.SECONDS.toNanos(3)
                && timing[2] >= sampleAfter
                && timing[2] != lastObservation) {
              lastObservation = timing[2];
              ages.add(timing[1]);
            }
            if (!inspected) {
              inspected = true;
              HalInspection.requestRoute(context, input.registration(), operation);
            }
          }
          if (operation == 2 && !tuned) throw new IllegalStateException("Tuning not confirmed");
        } finally {
          if (operation == 2 && input.registration() != null) {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (HalInspection.result() == null && System.nanoTime() < end) Thread.sleep(10);
            if (HalInspection.result() != null) {
              HalInspection.requestRoute(context, input.registration(), 3);
              while (HalInspection.result() == null && System.nanoTime() < end) Thread.sleep(10);
              restoreReport = String.valueOf(HalInspection.result());
            } else restoreReport = "Helper pending; closing owned route";
          }
        }
      }
      if (zeroSourceFrames == 0) throw new IllegalStateException("Zero source did not run");
      Collections.sort(ages);
      synchronized (CaptureProbe.class) {
        status =
            "Capture-only complete; frames="
                + frames
                + " zeroSourceFrames="
                + zeroSourceFrames
                + " timestampSamples="
                + ages.size()
                + " minAgeUs="
                + (ages.isEmpty() ? -1 : ages.get(0))
                + " medianAgeUs="
                + (ages.isEmpty() ? -1 : ages.get(ages.size() / 2))
                + " maxAgeUs="
                + (ages.isEmpty() ? -1 : ages.get(ages.size() - 1))
                + " ageSamplesUs="
                + ages
                + "; zero-only input; no sender or music playback; HALoperation="
                + operation
                + (operation == 2
                    ? " tuning=" + tuningReport + " restoration=" + restoreReport
                    : "");
      }
    } catch (Exception error) {
      synchronized (CaptureProbe.class) {
        status = "Capture-only unavailable: " + error.getClass().getSimpleName();
      }
    } finally {
      if (descriptor != null) {
        try {
          descriptor.close();
        } catch (java.io.IOException ignored) {
        }
      }
      RootConnector.close(lifetime);
      timer.shutdownNow();
      synchronized (CaptureProbe.class) {
        cancellation = null;
        running = false;
      }
    }
  }

  static synchronized void dump(PrintWriter writer) {
    writer.println("Capture probe running=" + running + " " + status);
  }

  private CaptureProbe() {}
}
