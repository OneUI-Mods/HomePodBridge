package dev.ryuya.homepodbridge;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

/** Public, session-owned Wi-Fi request. Held is not evidence of radio/driver activation. */
final class NetworkLease implements AutoCloseable {
  private WifiManager.WifiLock lock;
  private int unavailable;

  NetworkLease(Context context, boolean requested) {
    if (!requested) return;
    if (!onWifi(context)) {
      unavailable = 3;
      return;
    }
    try {
      WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
      if (wifi != null) {
        lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "HomePodBridge:audio");
        lock.setReferenceCounted(false);
        lock.acquire();
      }
    } catch (SecurityException denied) {
      close();
      unavailable = 4;
    } catch (RuntimeException unsupported) {
      close();
      unavailable = 5;
    }
  }

  int status(Context context) {
    if (lock == null || !lock.isHeld()) return unavailable;
    var process = new ActivityManager.RunningAppProcessInfo();
    ActivityManager.getMyMemoryState(process);
    boolean eligible =
        onWifi(context)
            && context.getSystemService(PowerManager.class).isInteractive()
            && process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    return eligible ? 2 : 1;
  }

  private static boolean onWifi(Context context) {
    var manager = context.getSystemService(ConnectivityManager.class);
    var network = manager.getBoundNetworkForProcess();
    if (network == null) network = manager.getActiveNetwork();
    var capabilities = network == null ? null : manager.getNetworkCapabilities(network);
    return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
  }

  @Override
  public void close() {
    WifiManager.WifiLock current = lock;
    lock = null;
    if (current != null && current.isHeld()) {
      try {
        current.release();
      } catch (RuntimeException ignored) {
      }
    }
  }
}
