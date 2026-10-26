package dev.ryuya.homepodbridge;

import android.content.Context;
import java.util.List;
import java.util.UUID;

/** No start commands or samples: only test root-owned Binder/FD delivery and closure. */
final class HalTransportProbe {
  static String run(Context context) {
    if (BridgeState.connected || BridgeState.volume != 0) return "HAL_PROBE_REFUSED";
    String nonce = UUID.randomUUID().toString();
    try {
      String apk = context.getApplicationInfo().sourceDir;
      if (!apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk")) return "HAL_PROBE_REFUSED";
      return "nonce="
          + nonce
          + " "
          + AudioServiceRecovery.run(
                  List.of(
                      "su",
                      "-c",
                      "CLASSPATH='"
                          + apk
                          + "' exec app_process /system/bin"
                          + " dev.ryuya.homepodbridge.halprobe.HalPortProbe streams "
                          + nonce
                          + " 44100"),
                  16384,
                  24,
                  true)
              .trim();
    } catch (Exception unavailable) {
      return "nonce=" + nonce + " HAL_PROBE_UNCONFIRMED";
    }
  }

  private HalTransportProbe() {}
}
