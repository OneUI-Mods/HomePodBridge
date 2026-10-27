package dev.ryuya.homepodbridge;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.function.Consumer;

/** Bounded zero-input check through the production capture/sender; never starts a music app. */
final class CommandPlayback implements AutoCloseable {
  private final Context context;
  private final Consumer<String> status;
  private final Handler main = new Handler(Looper.getMainLooper());
  private Discovery discovery;
  private long epoch;
  private CaptureService ownedService;
  private IBinder ownedLifetime;
  private boolean selectionPending;
  private boolean dedicatedHal;
  private AcousticProbe acoustic;
  private boolean testWhole, pulse, normal;

  CommandPlayback(Context context, Consumer<String> status) {
    this.context = context;
    this.status = status;
  }

  void start() {
    start(false);
  }

  void start(boolean dedicatedHal) {
    start(dedicatedHal, StreamSettings.load(context).wholeDevice(), false, false);
  }

  void startAcoustic(AcousticProbe probe) {
    start(true, false, false, false, probe);
  }

  void start(boolean dedicatedHal, boolean whole, boolean pulse, boolean normal) {
    start(dedicatedHal, whole, pulse, normal, null);
  }

  private void start(
      boolean dedicatedHal, boolean whole, boolean pulse, boolean normal, AcousticProbe probe) {
    if (BridgeState.capture != null) {
      status.accept("Existing sharing must stop before the test");
      if (probe != null) probe.close();
      return;
    }
    if (!RootConnector.available()) {
      status.accept("Zero-input transfer requires the system bridge");
      if (probe != null) probe.close();
      return;
    }
    close();
    this.acoustic = probe;
    this.dedicatedHal = dedicatedHal;
    this.testWhole = whole;
    this.pulse = pulse;
    this.normal = normal;
    selectionPending = false;
    long token = ++epoch;
    status.accept("Discovering one HomePod for a muted zero-input transfer");
    discovery =
        new Discovery(
            context,
            new Discovery.Listener() {
              public void changed(java.util.List<Receiver> receivers) {
                if (token != epoch || selectionPending || receivers.isEmpty()) return;
                if (receivers.size() != 1) {
                  status.accept("Ambiguous receivers; no transfer started");
                  close();
                  return;
                }
                Receiver receiver = receivers.get(0);
                selectionPending = true;
                if (discovery != null) {
                  discovery.close();
                  discovery = null;
                }
                BridgeState.receivers = java.util.List.copyOf(receivers);
                BridgeState.publish();
                try {
                  context.startForegroundService(
                      new Intent(context, CaptureService.class).putExtra("system", true));
                  waitForService(receiver, token, android.os.SystemClock.elapsedRealtime() + 3000);
                } catch (RuntimeException error) {
                  status.accept("Transfer start unavailable: " + error.getClass().getSimpleName());
                  close();
                }
              }

              public void failed() {
                if (token == epoch) {
                  status.accept("Receiver discovery unavailable");
                  close();
                }
              }
            });
    discovery.start();
    main.postDelayed(
        () -> {
          if (token == epoch) {
            close();
            status.accept("Bounded zero-input transfer ended");
          }
        },
        25000);
  }

  private void waitForService(Receiver receiver, long token, long deadline) {
    if (token != epoch) return;
    CaptureService service = BridgeState.capture;
    if (service != null) {
      ownedService = service;
      CaptureService.ConnectionCallback finished =
          success -> {
            if (token == epoch && acoustic != null) acoustic.connected(success);
            if (token == epoch)
              status.accept(
                  success
                      ? "Zero-input transfer connected at initial mute"
                      : "Zero-input transfer failed");
          };
      if (normal) service.select(receiver, BridgeState.MUSIC, finished);
      else if (dedicatedHal) service.selectZeroHalProbe(receiver, finished, testWhole, pulse);
      else service.selectZeroProbe(receiver, finished);
      ownedLifetime = service.lifetime();
      if (acoustic != null) acoustic.bind(ownedLifetime);
      return;
    }
    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
      status.accept("Capture service unavailable");
      close();
      return;
    }
    main.postDelayed(() -> waitForService(receiver, token, deadline), 50);
  }

  @Override
  public void close() {
    ++epoch;
    if (acoustic != null) {
      var closing = acoustic;
      acoustic = null;
      closing.close();
    }
    if (discovery != null) {
      discovery.close();
      discovery = null;
    }
    if (ownedService != null
        && BridgeState.capture == ownedService
        && ownedService.lifetime() == ownedLifetime) {
      ownedService.disconnect();
      context.stopService(new Intent(context, CaptureService.class));
    }
    ownedService = null;
    ownedLifetime = null;
  }
}
