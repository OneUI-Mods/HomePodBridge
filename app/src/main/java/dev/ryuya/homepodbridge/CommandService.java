package dev.ryuya.homepodbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** DUMP-protected command entry point; never launches an activity or starts audible playback. */
public final class CommandService extends Service {
  private final ExecutorService commands = Executors.newSingleThreadExecutor();
  private volatile String commandStatus = "Ready";
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private CommandPlayback playback;

  @Override
  public void onCreate() {
    super.onCreate();
    playback = new CommandPlayback(this, value -> commandStatus = value);
    NotificationManager manager = getSystemService(NotificationManager.class);
    NotificationChannel channel =
        new NotificationChannel(
            "timing-diagnostics",
            getString(R.string.tab_diagnostics),
            NotificationManager.IMPORTANCE_LOW);
    channel.setSound(null, null);
    manager.createNotificationChannel(channel);
    startForeground(
        2,
        new Notification.Builder(this, channel.getId())
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.diagnostics_command_running))
            .setSmallIcon(R.drawable.ic_speaker)
            .setSound(null)
            .setVibrate(new long[0])
            .setDefaults(0)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    RootConnector.request(this);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String command = intent == null ? "status" : intent.getStringExtra("command");
    if (command == null || command.equals("status")) return START_NOT_STICKY;
    if (command.equals("probe-export-audioserver")) {
      commandStatus = "Reading the fixed system audio server image";
      commands.execute(
          () -> commandStatus = "Audio server image: " + HalTransportProbe.exportAudioServer(this));
      return START_NOT_STICKY;
    }
    if (command.equals("probe-hal-period") || command.equals("probe-mixer-period")) {
      commandStatus = "Checking owned HAL period without playback";
      commands.execute(
          () ->
              commandStatus =
                  "HAL period: "
                      + HalTransportProbe.getterPeriod(this, command.equals("probe-mixer-period")));
      return START_NOT_STICKY;
    }
    if (command.equals("probe-output-period")) {
      commandStatus = "Checking private virtual output period without playback";
      commands.execute(
          () -> commandStatus = "Output period: " + HalTransportProbe.outputPeriod(this));
      return START_NOT_STICKY;
    }
    if (command.equals("probe-packet-handoff")) {
      commandStatus = "Checking zero-only packet assembly without network output";
      commands.execute(() -> commandStatus = "Packet handoff: " + PacketHandoffProbe.run(this));
      return START_NOT_STICKY;
    }
    if (command.equals("short-packets-on") || command.equals("short-packets-off")) {
      if (BridgeState.capture != null || BridgeState.connected || BridgeState.volume != 0) {
        commandStatus = "Stop sharing before changing diagnostic packet preference";
        return START_NOT_STICKY;
      }
      StreamSettings.load(this).withShortAudioPackets(command.endsWith("on")).save(this);
      commandStatus = "Short packet preference: " + command.endsWith("on");
      return START_NOT_STICKY;
    }
    if (java.util.List.of("probe-local-hal-steady", "probe-local-hal-752", "probe-local-hal-768")
        .contains(command)) {
      int target = command.endsWith("752") ? 752 : command.endsWith("768") ? 768 : 800;
      commandStatus = "Checking fixed zero-only reserve candidate";
      commands.execute(
          () -> commandStatus = "Local HAL: " + LocalHalProbe.run(this, target, false));
      return START_NOT_STICKY;
    }
    if (command.equals("probe-local-hal")) {
      commandStatus = "Checking zero-only capture without a receiver";
      commands.execute(() -> commandStatus = "Local HAL: " + LocalHalProbe.run(this));
      return START_NOT_STICKY;
    }
    if (command.equals("timing-independent") || command.equals("timing-linked")) {
      if (BridgeState.capture != null || BridgeState.connected || BridgeState.volume != 0) {
        commandStatus = "Stop sharing before changing diagnostic timing";
        return START_NOT_STICKY;
      }
      StreamSettings settings = StreamSettings.load(this);
      ReceiverTiming timing = ReceiverTiming.linked(settings.effectiveLatencyMs());
      if (command.equals("timing-independent")) {
        int minimum = intent.getIntExtra("minimum_ms", -1);
        int maximum = intent.getIntExtra("maximum_ms", -1);
        int presentation = intent.getIntExtra("presentation_ms", -1);
        if (minimum < 10
            || maximum > 2000
            || minimum > maximum
            || presentation < 10
            || presentation > 2000) {
          commandStatus = "Invalid independent timing";
          return START_NOT_STICKY;
        }
        timing = new ReceiverTiming(true, minimum, maximum, presentation);
      }
      settings.withReceiverTiming(timing).save(this);
      commandStatus = "Independent receiver timing: " + timing.independent();
      return START_NOT_STICKY;
    }
    if (command.equals("probe-policy-input") || command.equals("probe-policy-route")) {
      commandStatus = "Preparing record-free self-UID policy input";
      boolean routeOnly = command.equals("probe-policy-route");
      commands.execute(() -> commandStatus = HalTransportProbe.policyInput(this, routeOnly));
      return START_NOT_STICKY;
    }
    if (command.equals("stop-hal-probe")) {
      String nonce = intent.getStringExtra("owned_nonce");
      commandStatus = "Stopping specified prototype helper";
      commands.execute(() -> commandStatus = HalTransportProbe.stop(this, nonce));
      return START_NOT_STICKY;
    }
    if (command.equals("probe-hal-metrics")) {
      commandStatus = "Reading idle HAL resource counts";
      commands.execute(() -> commandStatus = HalTransportProbe.metrics(this));
      return START_NOT_STICKY;
    }
    if (dev.ryuya.homepodbridge.halprobe.ProbeScenario.forCommand(command) != null) {
      commandStatus = "Preparing open-only HAL transport probe";
      String requested = command;
      commands.execute(() -> commandStatus = HalTransportProbe.run(this, requested));
      return START_NOT_STICKY;
    }
    if (command.equals("recover-audio")) {
      String nonce = intent.getStringExtra("owned_nonce");
      commandStatus = "Checking explicit audio-service recovery";
      commands.execute(() -> commandStatus = AudioServiceRecovery.restart(this, nonce));
      return START_NOT_STICKY;
    }
    if (command.equals("reload-status")) {
      commandStatus = "Checking GalaxyReload CLI";
      commands.execute(() -> commandStatus = "GalaxyReload CLI: " + GalaxyReloadCli.check(this));
      return START_NOT_STICKY;
    }
    if (command.equals("reload-systemui")) {
      commandStatus = "Reloading through GalaxyReload CLI";
      commands.execute(
          () -> {
            commandStatus = "GalaxyReload CLI: " + GalaxyReloadCli.reload(this);
            main.post(() -> RootConnector.request(this));
          });
      return START_NOT_STICKY;
    }
    if (command.equals("hal-capture-on") || command.equals("hal-capture-off")) {
      StreamSettings.load(this).withDedicatedCapture(command.endsWith("on")).save(this);
      commandStatus = "Dedicated capture preference: " + command.endsWith("on");
      return START_NOT_STICKY;
    }
    if (command.equals("capture-scope-whole") || command.equals("capture-scope-selected")) {
      if (BridgeState.capture != null) {
        commandStatus = "Stop sharing before changing diagnostic scope";
        return START_NOT_STICKY;
      }
      StreamSettings.load(this).withWholeDevice(command.endsWith("whole")).save(this);
      commandStatus = "Whole-device capture preference: " + command.endsWith("whole");
      return START_NOT_STICKY;
    }
    if (java.util.List.of(
            "connect-zero",
            "connect-zero-hal",
            "connect-zero-hal-whole",
            "connect-zero-hal-cycle",
            "connect-idle-hal")
        .contains(command)) {
      boolean dedicated = command.startsWith("connect-zero-hal");
      boolean whole = command.equals("connect-zero-hal-whole");
      boolean pulse = command.equals("connect-zero-hal-cycle");
      boolean normal = command.equals("connect-idle-hal");
      commands.execute(
          () -> {
            try {
              long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
              while (!RootConnector.available() && System.nanoTime() < deadline) Thread.sleep(25);
              if (RootConnector.available())
                main.post(() -> playback.start(dedicated, whole, pulse, normal));
              else commandStatus = "System bridge unavailable";
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          });
      return START_NOT_STICKY;
    }
    if (command.equals("stop-transfer")) {
      playback.close();
      commandStatus = "Command-owned transfer stopped";
      return START_NOT_STICKY;
    }
    if (command.equals("phone-buffer-on") || command.equals("phone-buffer-off")) {
      StreamSettings.load(this).withDeviceBuffer(command.endsWith("on")).save(this);
      commandStatus = "Phone buffer reduction preference: " + command.endsWith("on");
      return START_NOT_STICKY;
    }
    if (command.equals("phone-buffer-balanced") || command.equals("phone-buffer-faster")) {
      int frames = command.endsWith("faster") ? 928 : 1856;
      StreamSettings.load(this).withDeviceBufferFrames(frames).save(this);
      commandStatus = "Phone buffer target preference: " + frames;
      return START_NOT_STICKY;
    }
    if (!java.util.List.of("baseline", "guard", "tuned", "lease", "brief", "minimum")
        .contains(command)) {
      commandStatus = "Unknown diagnostic command";
      return START_NOT_STICKY;
    }
    commandStatus = "Preparing " + command;
    String requested = command;
    commands.execute(
        () -> {
          try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!RootConnector.available() && System.nanoTime() < deadline) Thread.sleep(25);
            if (!RootConnector.available()) {
              commandStatus = "System bridge unavailable";
              return;
            }
            CaptureProbe.request(
                this,
                switch (requested) {
                  case "guard" -> 1;
                  case "tuned" -> 2;
                  case "lease" -> 3;
                  case "brief" -> 4;
                  case "minimum" -> 5;
                  default -> 0;
                });
            commandStatus = "Dispatched " + requested;
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            commandStatus = "Command cancelled";
          }
        });
    return START_NOT_STICKY;
  }

  @Override
  protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
    writer.println("Command service: " + commandStatus);
    writer.println("connected=" + BridgeState.connected + " volume=" + BridgeState.volume);
    writer.println(
        "captureBackend="
            + BridgeState.captureBackend
            + " dedicatedPreference="
            + StreamSettings.load(this).dedicatedCapture());
    try (var report = openFileInput("last-hal-capture.txt")) {
      writer.println(
          "Last HAL capture: "
              + new String(report.readNBytes(16384), java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.io.IOException absent) {
    }
    try (var report = openFileInput("last-hal-zero.txt")) {
      writer.println(
          "Last zero HAL result: "
              + new String(report.readNBytes(8192), java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.io.IOException absent) {
    }
    for (String name : java.util.List.of("last-capture-error.txt", "last-connection.txt")) {
      try (var report = openFileInput(name)) {
        writer.println(
            name
                + ": "
                + new String(report.readNBytes(8192), java.nio.charset.StandardCharsets.UTF_8));
      } catch (java.io.IOException absent) {
      }
    }
    StreamSettings settings = StreamSettings.load(this);
    writer.println(
        "shortAudioPackets="
            + settings.shortAudioPackets()
            + " requestedPacketFrames="
            + settings.requestedPacketFrames());
    writer.println(
        "independentReceiverTiming="
            + settings.receiverTiming().independent()
            + " requestedReceiverMinimumMs="
            + settings.requestedReceiverMinimumMs()
            + " requestedReceiverMaximumMs="
            + settings.requestedReceiverMaximumMs()
            + " requestedPresentationMs="
            + settings.requestedPresentationMs());
    writer.println(
        "wholeDevice="
            + settings.wholeDevice()
            + " deviceBuffer="
            + settings.deviceBuffer()
            + " effectiveDeviceBuffer="
            + settings.effectiveDeviceBuffer()
            + " deviceBufferFrames="
            + settings.deviceBufferFrames()
            + " receiverTargetMs="
            + settings.effectiveLatencyMs());
    CaptureProbe.dump(writer);
    HalInspection.dump(writer, false);
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    commands.shutdownNow();
    playback.close();
    CaptureProbe.cancel();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
