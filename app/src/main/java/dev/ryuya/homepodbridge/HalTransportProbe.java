package dev.ryuya.homepodbridge;

import android.content.Context;
import java.util.List;
import java.util.UUID;

/** No start commands or samples: only test root-owned Binder/FD delivery and closure. */
final class HalTransportProbe {
  static String run(Context context) {
    return execute(context, "streams", UUID.randomUUID().toString());
  }

  static String stop(Context context, String nonce) {
    if (!AudioServiceRecovery.validNonce(nonce)) return "HAL_PROBE_REFUSED";
    return execute(context, "stop", nonce);
  }

  private static String execute(Context context, String operation, String nonce) {
    if (BridgeState.connected || BridgeState.volume != 0) return "HAL_PROBE_REFUSED";
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
                          + "' exec timeout 20 app_process /system/bin"
                          + " dev.ryuya.homepodbridge.halprobe.HalPortProbe "
                          + operation
                          + " "
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
