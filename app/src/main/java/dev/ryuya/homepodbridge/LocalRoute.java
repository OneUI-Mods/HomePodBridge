package dev.ryuya.homepodbridge;

import android.os.Process;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Temporary, destination- and UID-scoped Wi-Fi route when a VPN prevents normal binding. */
final class LocalRoute implements AutoCloseable {
  private final java.lang.Process process;

  static LocalRoute acquire(String host) throws IOException, InterruptedException {
    if (!isLocalIpv4(host)) throw new IOException("Receiver is not on a private local network");
    int uid = Process.myUid();
    String selector =
        "pref 12990 to " + host + "/32 uidrange " + uid + "-" + uid + " lookup \"$iface\"";
    String command =
        "iface=$(getprop wifi.interface); [ -n \"$iface\" ] || iface=wlan0; "
            + "case \"$iface\" in wlan[0-9]*) ;; *) exit 2 ;; esac; "
            + "ip -4 route get "
            + host
            + " oif \"$iface\" >/dev/null 2>&1 || exit 3; "
            + "cleanup() { ip -4 rule del "
            + selector
            + " 2>/dev/null; }; "
            + "trap cleanup EXIT HUP INT TERM; "
            + "ip -4 rule add "
            + selector
            + " || exit 4; "
            + "echo HOMEPOD_ROUTE_READY; read -r lifetime";
    java.lang.Process helper =
        new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
    LocalRoute route = new LocalRoute(helper);
    BufferedReader response = new BufferedReader(new InputStreamReader(helper.getInputStream()));
    long deadline = System.nanoTime() + 20_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (response.ready()) {
        String line = response.readLine();
        if ("HOMEPOD_ROUTE_READY".equals(line)) return route;
      }
      if (!helper.isAlive()) break;
      Thread.sleep(50);
    }
    route.close();
    throw new IOException("Root permission is required to route local audio around this VPN");
  }

  static boolean isLocalIpv4(String host) {
    if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
    String[] parts = host.split("\\.");
    int[] ip = new int[4];
    for (int i = 0; i < 4; i++) {
      ip[i] = Integer.parseInt(parts[i]);
      if (ip[i] > 255) return false;
    }
    return ip[0] == 10
        || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31)
        || (ip[0] == 192 && ip[1] == 168)
        || (ip[0] == 169 && ip[1] == 254);
  }

  private LocalRoute(java.lang.Process process) {
    this.process = process;
  }

  @Override
  public void close() {
    try {
      process.getOutputStream().close();
    } catch (IOException ignored) {
    }
    try {
      if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroy();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroy();
    }
  }
}
