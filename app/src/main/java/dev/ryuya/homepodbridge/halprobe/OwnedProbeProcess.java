package dev.ryuya.homepodbridge.halprobe;

import java.util.UUID;

final class OwnedProbeProcess {
  static boolean matches(String command, String status, String nonce) {
    try {
      if (!UUID.fromString(nonce).toString().equals(nonce)) return false;
    } catch (RuntimeException invalid) {
      return false;
    }
    String suffix =
        "dev.ryuya.homepodbridge.halprobe.HalPortProbe\u0000streams\u0000"
            + nonce
            + "\u000044100\u0000";
    return command.endsWith(suffix) && status.matches("(?s).*\\nUid:\\s+0\\s+0\\s+0\\s+0\\s*\\n.*");
  }

  private OwnedProbeProcess() {}
}
