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
    if (operation < 0 || operation > 5) return;
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
    var leaseExecutor = Executors.newSingleThreadExecutor();
    OwnedAudioPath lease = null;
    var leaseUpdate = new java.util.concurrent.atomic.AtomicReference<OwnedAudioPath.Update>();
    ArrayList<Long> ages = new ArrayList<>();
    long frames = 0;
    long zeroSourceFrames = 0;
    long[] lastTiming = null;
    int timingPolls = 0, missingTimingPolls = 0;
    String tuningReport = "", restoreReport = "";
    int tuneOperation = operation == 4 ? 4 : operation == 5 ? 6 : operation;
    int restoreOperation = operation == 4 ? 5 : operation == 5 ? 7 : 3;
    int targetFrames = operation == 4 ? 928 : operation == 5 ? 464 : 1856;
    boolean directTuning = operation == 2 || operation == 4 || operation == 5;
    try {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
      StreamSettings settings = StreamSettings.load(context);
      if (!settings.wholeDevice()) throw new IllegalStateException("Whole-device mode required");
      if (!settings.lowLatency() || settings.effectiveLatencyMs() != 50)
        throw new IllegalStateException("The 50 ms low-latency preset is required for comparison");
      if (operation == 3 && !settings.effectiveDeviceBuffer())
        throw new IllegalStateException(
            "Device buffer reduction must be enabled for the lease test");
      RootConnector.Input input = RootConnector.open(BridgeState.MUSIC, lifetime, settings, true);
      descriptor = input.pipe();
      if (operation == 3) {
        lease =
            new OwnedAudioPath(
                op -> HalInspection.operateRoute(context, input.registration(), op),
                leaseExecutor,
                leaseUpdate::set,
                settings.deviceBufferFrames());
        lease.start();
      }
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
      boolean inspected = operation == 3;
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
            timingPolls++;
            if (timing == null) missingTimingPolls++;
            else lastTiming = timing;
            if (timing != null && timing.length >= 11) zeroSourceFrames = timing[10];
            if (directTuning && inspected && !tuned) {
              String report = HalInspection.result();
              if (report != null) {
                tuningReport = report;
                if (!report.contains("Owned reserve changed: 2587 -> " + targetFrames))
                  throw new IllegalStateException("Owned tuning was refused");
                tuned = true;
                sampleAfter = now + TimeUnit.SECONDS.toNanos(2);
              }
            }
            if (operation == 3 && !tuned) {
              OwnedAudioPath.Update update = leaseUpdate.get();
              if (update != null && update.state() == OwnedAudioPath.State.ACTIVE) {
                tuned = true;
                tuningReport = update.report();
                sampleAfter = now + TimeUnit.SECONDS.toNanos(2);
              } else if (update != null && update.state() != OwnedAudioPath.State.CHECKING)
                throw new IllegalStateException("Production lease did not activate");
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
              HalInspection.requestRoute(context, input.registration(), tuneOperation);
            }
          }
          if (operation >= 2 && !tuned) throw new IllegalStateException("Tuning not confirmed");
        } finally {
          if (lease != null) {
            lease.close();
            leaseExecutor.shutdown();
            leaseExecutor.awaitTermination(2, TimeUnit.SECONDS);
            restoreReport = lease.restorationReport();
          }
          if (directTuning && input.registration() != null) {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (HalInspection.result() == null && System.nanoTime() < end) Thread.sleep(10);
            if (HalInspection.result() != null) {
              if (HalInspection.result().contains("writeAttempts=0")) {
                restoreReport = "No restoration needed; writeAttempts=0";
              } else {
                HalInspection.requestRoute(context, input.registration(), restoreOperation);
                while (HalInspection.result() == null && System.nanoTime() < end) Thread.sleep(10);
                restoreReport = String.valueOf(HalInspection.result());
              }
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
                + (operation >= 2
                    ? " tuning=" + tuningReport + " restoration=" + restoreReport
                    : "");
      }
    } catch (Exception error) {
      synchronized (CaptureProbe.class) {
        status =
            "Capture-only unavailable: "
                + error.getClass().getSimpleName()
                + (error instanceof IllegalStateException ? " " + error.getMessage() : "")
                + "; HALoperation="
                + operation
                + " frames="
                + frames
                + " zeroSourceFrames="
                + zeroSourceFrames
                + " timestampSamples="
                + ages.size()
                + " timingPolls="
                + timingPolls
                + " missingTimingPolls="
                + missingTimingPolls
                + " lastTiming="
                + java.util.Arrays.toString(lastTiming)
                + " tuning="
                + tuningReport
                + " restoration="
                + restoreReport;
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
      leaseExecutor.shutdownNow();
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
