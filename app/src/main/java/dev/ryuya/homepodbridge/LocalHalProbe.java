package dev.ryuya.homepodbridge;

import android.content.Context;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONObject;

/**
 * Bounded self-UID, fixed-zero capture through the actual app pipe; no receiver or network sender.
 */
final class LocalHalProbe {
  static String run(Context context) {
    return run(context, 800, true);
  }

  static String run(Context context, int target, boolean pulse) {
    return run(context, target, pulse, false);
  }

  static String run(Context context, int target, boolean pulse, boolean shortPeriod) {
    return run(
        context,
        target,
        pulse,
        shortPeriod,
        dev.ryuya.homepodbridge.halprobe.PhoneTiming.fastest());
  }

  static String runTiming(Context context, dev.ryuya.homepodbridge.halprobe.PhoneTiming timing) {
    return run(context, 800, false, true, timing);
  }

  private static String run(
      Context context,
      int target,
      boolean pulse,
      boolean shortPeriod,
      dev.ryuya.homepodbridge.halprobe.PhoneTiming timing) {
    if (BridgeState.capture != null || BridgeState.connected || BridgeState.volume != 0)
      return "LOCAL_HAL_BUSY";
    JSONObject result = new JSONObject();
    RootHalInput owner = null;
    boolean startupCleanupSafe = true;
    try {
      owner =
          shortPeriod
              ? new RootHalInput(context, BridgeState.MUSIC, false, true, pulse, timing)
              : new RootHalInput(context, BridgeState.MUSIC, false, true, pulse, target);
      PcmPipe pipe =
          new PcmPipe(
              Channels.newChannel(owner.input()), true, System::nanoTime, PcmPipe.HAL_MAGIC);
      ByteBuffer pcm = ByteBuffer.allocateDirect(176);
      var transits = new ArrayList<Long>();
      long frames = 0, blocks = 0, pendingSum = 0, active = 0, maximumPending = 0, idleBlocks = 0;
      long begin = System.nanoTime(), firstActiveAt = -1, steadyIdle = 0;
      while (System.nanoTime() - begin < 10_000_000_000L) {
        if (Thread.currentThread().isInterrupted()
            || BridgeState.capture != null
            || BridgeState.connected
            || BridgeState.volume != 0)
          throw new java.io.IOException("Sharing changed during zero-only test");
        int read = pipe.read(pcm);
        if (read != BufferLimits.DEDICATED_FRAMES * 4)
          throw new java.io.IOException("Unexpected block");
        for (int i = 0; i < read; i++)
          if (pcm.get(i) != 0) throw new java.io.IOException("Nonzero diagnostic PCM");
        frames += read / 4;
        blocks++;
        long transit = pipe.transitMicros();
        if (transit >= 0) transits.add(transit);
        long pending = pipe.pendingFrames();
        if (pending < 0) {
          idleBlocks++;
          if (System.nanoTime() - begin >= 500_000_000L) steadyIdle++;
        } else {
          if (firstActiveAt < 0) firstActiveAt = (System.nanoTime() - begin) / 1000;
          if (System.nanoTime() - begin >= 500_000_000L) {
            pendingSum += pending;
            active++;
            maximumPending = Math.max(maximumPending, pending);
          }
        }
      }
      long elapsedUs = (System.nanoTime() - begin) / 1000;
      Collections.sort(transits);
      boolean realtime =
          frames * 1_000_000L >= elapsedUs * 44100L * 95 / 100
              && frames * 1_000_000L <= elapsedUs * 44100L * 105 / 100;
      result
          .put("targetFillFrames", shortPeriod ? timing.reserveFrames() : target)
          .put("shortMixerPeriod", shortPeriod)
          .put("requestedPeriodMs", shortPeriod ? timing.periodMs() : 21)
          .put("producerPeriodFrames", shortPeriod ? timing.periodFrames() : 928)
          .put("nonce", owner.nonce)
          .put("frames", frames)
          .put("blocks", blocks)
          .put("elapsedUs", elapsedUs)
          .put("realtime", realtime)
          .put("framesPerRead", BufferLimits.DEDICATED_FRAMES)
          .put("zeroOnly", true)
          .put("networkStarted", false)
          .put("idleBlocks", idleBlocks)
          .put("steadyIdleBlocks", steadyIdle)
          .put("firstActiveUs", firstActiveAt)
          .put("steadyMeanPendingFrames", active == 0 ? -1 : pendingSum / active)
          .put("steadyMaximumPendingFrames", maximumPending)
          .put("medianTransitUs", percentile(transits, 50))
          .put("p95TransitUs", percentile(transits, 95))
          .put("maximumTransitUs", percentile(transits, 100));
    } catch (Exception error) {
      if (error instanceof RootHalInput.StartupException startup)
        startupCleanupSafe = startup.safeFallback;
      try {
        result.put("failure", error.getClass().getSimpleName() + ": " + error.getMessage());
      } catch (org.json.JSONException ignored) {
      }
    } finally {
      boolean cleaned = owner == null && startupCleanupSafe;
      if (owner != null) {
        boolean interrupted = Thread.interrupted();
        owner.close();
        cleaned = owner.awaitCleanup(5000);
        if (interrupted) Thread.currentThread().interrupt();
      }
      if (!cleaned) CaptureService.blockUnconfirmedHalCleanup();
      try {
        result.put("cleanupConfirmed", cleaned);
      } catch (org.json.JSONException ignored) {
      }
    }
    return result.toString();
  }

  private static long percentile(ArrayList<Long> sorted, int percent) {
    return sorted.isEmpty()
        ? -1
        : sorted.get(Math.min(sorted.size() - 1, (sorted.size() - 1) * percent / 100));
  }

  private LocalHalProbe() {}
}
