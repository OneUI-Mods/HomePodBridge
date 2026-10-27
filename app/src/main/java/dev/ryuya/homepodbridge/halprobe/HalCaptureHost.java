package dev.ryuya.homepodbridge.halprobe;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Looper;
import java.io.DataOutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Root policy owner for a single authenticated app socket, including idle/resume lifetimes. */
public final class HalCaptureHost {
  public static void main(String[] args) {
    JSONObject result = new JSONObject();
    boolean allocations = false;
    ReadOnlyHalState state = null;
    String baseline = null;
    String registration = null;
    HalPeriodExperiment[] periods = {null, null};
    try {
      if ((args.length != 3 && args.length != 5)
          || !UUID.fromString(args[0]).toString().equals(args[0])
          || !args[2].matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
          || args[2].length() > 255) throw new SecurityException();
      HalPortProbe.rootPreflight();
      state = new ReadOnlyHalState();
      baseline = state.snapshot().toString();
      if (Looper.myLooper() == null) Looper.prepare();
      Class<?> activity = Class.forName("android.app.ActivityThread");
      Object thread = activity.getMethod("systemMain").invoke(null);
      Context context = (Context) activity.getMethod("getSystemContext").invoke(thread);
      int bridgeUid =
          context.getPackageManager().getApplicationInfo("dev.ryuya.homepodbridge", 0).uid;
      int selectedUid = context.getPackageManager().getApplicationInfo(args[2], 0).uid;
      if (selectedUid == bridgeUid) throw new SecurityException("Cannot capture Bridge itself");
      CaptureScope scope = CaptureScope.parse(args[1], selectedUid, bridgeUid);
      boolean shortPeriod = args[1].endsWith("-short");
      if (args.length == 5 && !shortPeriod) throw new SecurityException();
      PhoneTiming timing =
          args.length == 5
              ? new PhoneTiming(Integer.parseInt(args[3]), Integer.parseInt(args[4]))
              : PhoneTiming.fastest();
      int target =
          switch (args[1]) {
            case "test-target-752" -> 752;
            case "test-target-768" -> 768;
            default -> 800;
          };
      try (var socket = new LocalSocket()) {
        socket.connect(
            new LocalSocketAddress("hpb-live-" + args[0], LocalSocketAddress.Namespace.ABSTRACT));
        if (socket.getPeerCredentials().getUid() != bridgeUid)
          throw new SecurityException("Bridge client required");
        AtomicBoolean stopped = new AtomicBoolean();
        Thread control =
            new Thread(
                () -> {
                  try {
                    socket.getInputStream().read();
                  } catch (java.io.IOException ignored) {
                  } finally {
                    stopped.set(true);
                    try {
                      socket.close();
                    } catch (java.io.IOException ignored) {
                    }
                  }
                },
                "HomePod-HAL-client");
        control.setDaemon(true);
        control.start();
        allocations = true;
        try (var policy =
            new RegisteredPolicy(
                context,
                scope,
                address -> {
                  if (shortPeriod) {
                    if (stopped.get()) throw new java.io.IOException("Client stopped");
                    periods[0] = new HalPeriodExperiment(address, false, timing);
                    periods[1] = new HalPeriodExperiment(address, true, timing);
                  }
                })) {
          registration = policy.address;
          var output = socket.getOutputStream();
          HalPortProbe.Ready ready =
              () -> {
                DataOutputStream message = new DataOutputStream(output);
                message.writeInt(0x48505231);
                message.writeUTF(args[0]);
                message.writeUTF(policy.address);
                message.flush();
              };
          if (shortPeriod) {
            result.put(
                "shortPeriod", HalPeriodExperiment.requireOutput(state, policy.address, timing));
            result.put(
                "capture",
                HalPortProbe.livePeriodCapture(
                    policy.address,
                    UUID.fromString(args[0]),
                    scope.zeroOnly(),
                    scope.pulse(),
                    output,
                    stopped::get,
                    ready,
                    socket.getFileDescriptor(),
                    () -> {
                      periods[1].close();
                      periods[0].close();
                      if (stopped.get()) throw new java.io.IOException("Client stopped");
                    },
                    timing));
          } else {
            result.put(
                "capture",
                HalPortProbe.inputSession(
                    policy.address,
                    UUID.fromString(args[0]),
                    target,
                    false,
                    true,
                    scope.zeroOnly(),
                    scope.pulse(),
                    output,
                    stopped::get,
                    ready,
                    socket.getFileDescriptor()));
          }
        }
      }
    } catch (Throwable failure) {
      try {
        result.put("failure", failure.getClass().getSimpleName());
      } catch (Exception ignored) {
      }
    }
    for (int i = periods.length - 1; i >= 0; i--)
      if (periods[i] != null)
        try {
          periods[i].close();
        } catch (Exception error) {
          try {
            result.put("periodCleanupFailure", error.getClass().getSimpleName());
          } catch (Exception ignored) {
          }
        }
    boolean periodClean = true;
    for (var period : periods)
      if (period != null && !period.cleanupConfirmed()) periodClean = false;
    try {
      result.put("periodCleanupConfirmed", periodClean);
    } catch (Exception ignored) {
    }
    try {
      boolean restored = !allocations;
      boolean ownedReleased = !allocations;
      if (allocations && state != null)
        for (int i = 0; i < 60; i++) {
          JSONObject after = state.snapshot();
          restored = baseline.equals(after.toString());
          boolean ownPort = false;
          var ports = after.getJSONArray("ports");
          for (int j = 0; j < ports.length(); j++)
            if (registration != null
                && registration.equals(ports.getJSONObject(j).optString("address"))) ownPort = true;
          JSONObject capture = result.optJSONObject("capture");
          ownedReleased =
              registration != null
                  && !ownPort
                  && capture != null
                  && capture.optBoolean("ownedInputReleased");
          if (restored || ownedReleased) break;
          Thread.sleep(50);
        }
      result
          .put("resourcesRestoredExactly", restored)
          .put("ownedResourcesReleased", ownedReleased)
          .put("noAllocations", !allocations);
      if (!restored && baseline != null)
        result.put("before", new JSONObject(baseline)).put("after", state.snapshot());
    } catch (Exception uncertain) {
      try {
        result.put("resourcesRestoredExactly", false);
      } catch (Exception ignored) {
      }
    }
    System.out.println(result);
    System.out.flush();
    if (periodClean
        && (result.optBoolean("resourcesRestoredExactly")
            || result.optBoolean("ownedResourcesReleased"))) System.exit(0);
  }

  private HalCaptureHost() {}
}
