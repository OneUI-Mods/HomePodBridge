package dev.ryuya.homepodbridge;

import android.content.Context;
import android.content.pm.PackageManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Fixed read-only command to the installed, signer-pinned GalaxyReload CLI. */
final class GalaxyReloadCli {
  private static final String PACKAGE = "dev.ryuya.galaxyreload";
  private static final String SIGNER =
      "52a22de1aed8b3c3d34c02f8fe0f0206eaee25081def64d8182ca5930cbf85fb";

  static String check(Context context) {
    return execute(context, "check");
  }

  static String reload(Context context) {
    if (BridgeState.connected || BridgeState.volume != 0 || BridgeState.capture != null)
      return "BUSY";
    return execute(context, "reload");
  }

  private static String execute(Context context, String action) {
    Process helper = null;
    try {
      var info =
          context
              .getPackageManager()
              .getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
      if (info.getLongVersionCode() < 2
          || info.signingInfo == null
          || info.signingInfo.getApkContentsSigners().length != 1)
        return "UNSUPPORTED_GALAXY_RELOAD";
      String signer =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(info.signingInfo.getApkContentsSigners()[0].toByteArray()));
      if (!SIGNER.equals(signer)) return "UNTRUSTED_GALAXY_RELOAD";
      String apk = context.getPackageManager().getApplicationInfo(PACKAGE, 0).sourceDir;
      if (!apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk")) return "INVALID_MODULE_PATH";
      if (action.equals("reload")) {
        String own = context.getApplicationInfo().sourceDir;
        if (!own.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk")) return "INVALID_MODULE_PATH";
        String prepared =
            AudioServiceRecovery.run(
                    java.util.List.of(
                        "su",
                        "-c",
                        "CLASSPATH='"
                            + own
                            + "' exec app_process /system/bin"
                            + " dev.ryuya.homepodbridge.GalaxyReloadPrepare"),
                    256,
                    8)
                .trim();
        if (!prepared.equals("PREPARED")) return "RELOAD_CHECK_UNAVAILABLE";
      }
      helper =
          new ProcessBuilder(
                  "su",
                  "-c",
                  "CLASSPATH='"
                      + apk
                      + "' exec app_process /system/bin dev.ryuya.galaxyreload.ReloadCommand "
                      + action)
              .redirectError(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")))
              .start();
      if (!helper.waitFor(15, TimeUnit.SECONDS)) return "RELOAD_CHECK_TIMEOUT";
      String status =
          new String(helper.getInputStream().readNBytes(256), StandardCharsets.US_ASCII).trim();
      return Set.of(
                      "READY",
                      "SETUP",
                      "ROOT_REQUIRED",
                      "UNSUPPORTED",
                      "MODULE_REQUIRED",
                      "LOCKED",
                      "SCREEN_OFF",
                      "NO_SYSTEMUI",
                      "UNLOCK_NEEDED",
                      "FAILED")
                  .contains(status)
              || Set.of("RELOADED", "STATE_CHANGED", "BUSY").contains(status)
          ? status
          : "RELOAD_CHECK_UNAVAILABLE";
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return "RELOAD_CHECK_CANCELLED";
    } catch (Exception unavailable) {
      return "RELOAD_CHECK_UNAVAILABLE";
    } finally {
      if (helper != null) helper.destroy();
    }
  }

  private GalaxyReloadCli() {}
}
