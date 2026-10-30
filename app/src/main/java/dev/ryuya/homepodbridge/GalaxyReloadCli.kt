package dev.ryuya.homepodbridge

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.List
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmStatic

/* Fixed check/reload commands to the installed, signer-pinned GalaxyReload CLI. */
object GalaxyReloadCli {
    private const val PACKAGE: String = "dev.ryuya.galaxyreload"
    private const val SIGNER: String =
        "52a22de1aed8b3c3d34c02f8fe0f0206eaee25081def64d8182ca5930cbf85fb"

    @JvmStatic
    fun check(context: Context): String? {
        return GalaxyReloadCli.execute(context, "check")
    }

    @JvmStatic
    fun reload(context: Context): String? {
        if (BridgeState.connected || BridgeState.volume != 0 || BridgeState.capture != null)
            return "BUSY"
        return GalaxyReloadCli.execute(context, "reload")
    }

    @JvmStatic
    private fun execute(context: Context, action: String): String? {
        var helper: Process? = null
        try {
            val info =
                context
                    .getPackageManager()
                    .getPackageInfo(
                        GalaxyReloadCli.PACKAGE,
                        PackageManager.GET_SIGNING_CERTIFICATES,
                    )
            if (
                info.getLongVersionCode() < 2 ||
                    info.signingInfo == null ||
                    info!!.signingInfo!!.getApkContentsSigners().size != 1
            )
                return "UNSUPPORTED_GALAXY_RELOAD"
            val signer =
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(info!!.signingInfo!!.getApkContentsSigners()[0].toByteArray())
                    )
            if (GalaxyReloadCli.SIGNER != signer) return "UNTRUSTED_GALAXY_RELOAD"
            val apk =
                context.getPackageManager().getApplicationInfo(GalaxyReloadCli.PACKAGE, 0).sourceDir
            if (!apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex()))
                return "INVALID_MODULE_PATH"
            if (action == "reload") {
                val own = context.getApplicationInfo().sourceDir
                if (!own.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex()))
                    return "INVALID_MODULE_PATH"
                val prepared =
                    AudioServiceRecovery.run(
                            List.of<String?>(
                                "su",
                                "-c",
                                ("CLASSPATH='" +
                                    own +
                                    "' exec app_process /system/bin" +
                                    " dev.ryuya.homepodbridge.GalaxyReloadPrepare"),
                            ),
                            256,
                            8,
                        )!!
                        .trim { it <= ' ' }
                if (prepared != "PREPARED") return "RELOAD_CHECK_UNAVAILABLE"
            }
            helper =
                ProcessBuilder(
                        "su",
                        "-c",
                        ("CLASSPATH='" +
                            apk +
                            "' exec app_process /system/bin dev.ryuya.galaxyreload.ReloadCommand " +
                            action),
                    )
                    .redirectError(Redirect.to(File("/dev/null")))
                    .start()
            if (!helper.waitFor(15, TimeUnit.SECONDS)) return "RELOAD_CHECK_TIMEOUT"
            val status =
                String(helper.getInputStream().readNBytes(256), StandardCharsets.US_ASCII).trim {
                    it <= ' '
                }
            return if (
                java.util.Set.of<String?>(
                        "READY",
                        "SETUP",
                        "ROOT_REQUIRED",
                        "UNSUPPORTED",
                        "MODULE_REQUIRED",
                        "LOCKED",
                        "SCREEN_OFF",
                        "NO_SYSTEMUI",
                        "UNLOCK_NEEDED",
                        "FAILED",
                    )
                    .contains(status) ||
                    java.util.Set.of<String?>("RELOADED", "STATE_CHANGED", "BUSY").contains(status)
            )
                status
            else "RELOAD_CHECK_UNAVAILABLE"
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return "RELOAD_CHECK_CANCELLED"
        } catch (unavailable: Exception) {
            return "RELOAD_CHECK_UNAVAILABLE"
        } finally {
            if (helper != null) helper.destroy()
        }
    }
}
