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
    if (command.equals("connect-zero")) {
      commands.execute(
          () -> {
            try {
              long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
              while (!RootConnector.available() && System.nanoTime() < deadline) Thread.sleep(25);
              if (RootConnector.available()) main.post(playback::start);
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
    if (!java.util.List.of("baseline", "guard", "tuned", "lease").contains(command)) {
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
                requested.equals("lease")
                    ? 3
                    : requested.equals("tuned") ? 2 : requested.equals("guard") ? 1 : 0);
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
    StreamSettings settings = StreamSettings.load(this);
    writer.println(
        "deviceBuffer="
            + settings.deviceBuffer()
            + " effectiveDeviceBuffer="
            + settings.effectiveDeviceBuffer()
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
