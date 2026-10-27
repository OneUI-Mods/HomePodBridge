package dev.ryuya.homepodbridge;

import android.content.Context;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

/** Measures fixed-zero capture-completion to ALAC readiness; never opens a receiver or network. */
final class PacketHandoffProbe {
  static String run(Context context) {
    if (BridgeState.capture != null || BridgeState.connected || BridgeState.volume != 0)
      return "PACKET_PROBE_BUSY";
    RootHalInput owner = null;
    boolean safeStartup = true, codecOpened = false;
    JSONObject result = new JSONObject();
    try {
      if (!(codecOpened = NativeSender.openZeroCodecProbe()))
        throw new java.io.IOException("Codec probe unavailable");
      owner = new RootHalInput(context, BridgeState.MUSIC, false, true, false);
      PcmPipe pipe =
          new PcmPipe(
              Channels.newChannel(owner.input()), true, System::nanoTime, PcmPipe.HAL_MAGIC);
      ByteBuffer block = ByteBuffer.allocateDirect(176);
      ByteBuffer packet = ByteBuffer.allocateDirect(1408);
      JSONArray phases = new JSONArray();
      for (int packetFrames : new int[] {352, 88}) {
        packet.clear().limit(packetFrames * 4);
        var ages = new ArrayList<Long>();
        long firstReadAt = 0, encodedUs = 0, encodedBytes = 0;
        long begin = System.nanoTime();
        while (System.nanoTime() - begin < 4_000_000_000L) {
          if (Thread.currentThread().isInterrupted()
              || BridgeState.capture != null
              || BridgeState.connected
              || BridgeState.volume != 0)
            throw new java.io.IOException("Sharing changed during zero-only probe");
          int read = pipe.read(block);
          if (read != BufferLimits.DEDICATED_FRAMES * 4)
            throw new java.io.IOException("Unexpected capture block");
          for (int i = 0; i < read; i++)
            if (block.get(i) != 0) throw new java.io.IOException("Nonzero probe PCM");
          if (packet.position() == 0) {
            long transit = pipe.transitMicros();
            if (transit < 0) throw new java.io.IOException("Missing capture completion timestamp");
            firstReadAt = System.nanoTime() - transit * 1000;
          }
          packet.put(block);
          if (!packet.hasRemaining()) {
            packet.flip();
            long[] encoded = NativeSender.encodeZeroProbe(packet, packetFrames);
            if (encoded == null || encoded.length != 2)
              throw new java.io.IOException("Zero PCM encoding failed");
            long age = (System.nanoTime() - firstReadAt) / 1000;
            if (System.nanoTime() - begin >= 250_000_000L) ages.add(age);
            encodedUs += encoded[0];
            encodedBytes += encoded[1];
            packet.clear().limit(packetFrames * 4);
          }
        }
        Collections.sort(ages);
        phases.put(
            new JSONObject()
                .put("packetFrames", packetFrames)
                .put("readUnitFrames", BufferLimits.DEDICATED_FRAMES)
                .put("observations", ages.size())
                .put("encodedBytes", encodedBytes)
                .put("totalEncodeUs", encodedUs)
                .put("firstReadToEncodedMedianUs", percentile(ages, 50))
                .put("firstReadToEncodedP95Us", percentile(ages, 95))
                .put("firstReadToEncodedMaximumUs", percentile(ages, 100)));
      }
      result.put("phases", phases).put("zeroOnly", true).put("networkStarted", false);
    } catch (Exception error) {
      if (error instanceof RootHalInput.StartupException startup)
        safeStartup = startup.safeFallback;
      try {
        result.put("failure", error.getClass().getSimpleName() + ": " + error.getMessage());
      } catch (org.json.JSONException ignored) {
      }
    } finally {
      if (codecOpened) NativeSender.closeZeroCodecProbe();
      boolean cleaned = owner == null && safeStartup;
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
    return sorted.isEmpty() ? -1 : sorted.get((sorted.size() - 1) * percent / 100);
  }

  private PacketHandoffProbe() {}
}
