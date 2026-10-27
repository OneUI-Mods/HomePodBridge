package dev.ryuya.homepodbridge.halprobe;

import java.util.List;

/** Fail-closed ownership decision for the closed root-self-UID getter diagnostic. */
final class PeriodRouteGuard {
  record Port(int id, int type, String address) {}

  record Config(int id, int port, int rate, boolean stereo, boolean pcm16, int handle) {}

  static boolean allows(List<Port> ports, List<Config> configs, String address, int id, int port) {
    if (address == null
        || !address.matches("[0-9]+:ap:[0-9]+mixp:[0-9]+")
        || id <= 0
        || (port != 3 && port != 4)) return false;
    int matching = 0, activeMixes = 0, deviceConfigs = 0;
    var ids = new java.util.HashSet<Integer>();
    for (Config c : configs) {
      if (c.id() <= 0 || !ids.add(c.id())) return false;
      if (c.port() == port) activeMixes++;
      if (c.id() == id
          && c.port() == port
          && c.rate() == 44100
          && c.stereo()
          && c.pcm16()
          && c.handle() > 0) matching++;
      for (Port p : ports) {
        if (p.id() != c.port() || (p.type() != 143 && p.type() != 11)) continue;
        if (!address.equals(p.address())) return false;
        if (p.type() == (port == 3 ? 143 : 11)) deviceConfigs++;
      }
    }
    return matching == 1 && activeMixes == 1 && deviceConfigs == 1;
  }

  private PeriodRouteGuard() {}
}
