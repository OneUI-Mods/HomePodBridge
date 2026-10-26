package dev.ryuya.homepodbridge.halprobe;

import java.util.UUID;

final class OwnedProbeProcess {
  static boolean matches(String command, String status, String nonce) {
    try {
      if (!UUID.fromString(nonce).toString().equals(nonce)) return false;
    } catch (RuntimeException invalid) {
      return false;
    }
    if (!status.matches("(?s).*\\nUid:\\s+0\\s+0\\s+0\\s+0\\s*\\n.*")) return false;
    for (var scenario : ProbeScenario.values()) {
      String suffix =
          "dev.ryuya.homepodbridge.halprobe.HalPortProbe\u0000"
              + scenario.operation
              + "\u0000"
              + nonce
              + "\u000044100\u0000";
      if (command.endsWith(suffix)) return true;
    }
    return matchesClosedCycle(command, nonce);
  }

  static boolean matchesClosedCycle(String command, String nonce) {
    return command.endsWith(
        "dev.ryuya.homepodbridge.halprobe.HalCaptureHost\u0000"
            + nonce
            + "\u0000test-cycle\u0000com.apple.android.music\u0000");
  }

  private OwnedProbeProcess() {}
}
