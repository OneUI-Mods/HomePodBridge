package dev.ryuya.homepodbridge

import android.content.Context
import dev.ryuya.homepodbridge.halprobe.ProbeScenario
import java.util.List
import java.util.UUID
import kotlin.jvm.JvmStatic

/* Fixed silent diagnostics for owned virtual routes; never accepts caller audio or shell text. */
object HalTransportProbe {
    @JvmStatic
    fun run(context: Context, command: String?): String? {
        val scenario = ProbeScenario.forCommand(command)
        if (scenario == null) return "HAL_PROBE_REFUSED"
        return HalTransportProbe.execute(context, scenario.operation, UUID.randomUUID().toString())
    }

    @JvmStatic
    fun metrics(context: Context): String? {
        return HalTransportProbe.execute(context, "metrics", UUID.randomUUID().toString())
    }

    @JvmStatic
    fun outputPeriod(context: Context): String? {
        return HalTransportProbe.execute(context, "output-period", UUID.randomUUID().toString())
    }

    @JvmStatic
    fun getterPeriod(context: Context, mixer: Boolean): String? {
        return HalTransportProbe.execute(
            context,
            if (mixer) "getter-period-mixer" else "getter-period",
            UUID.randomUUID().toString(),
        )
    }

    @JvmStatic
    fun ownedPeriod(context: Context, period: Int): String? {
        if (period < 2 || period > 21) return "HAL_PROBE_REFUSED"
        return HalTransportProbe.execute(
            context,
            "getter-period-step-" + period,
            UUID.randomUUID().toString(),
        )
    }

    @JvmStatic
    fun periodCapture(context: Context): String? {
        return HalTransportProbe.execute(
            context,
            "getter-period-capture",
            UUID.randomUUID().toString(),
        )
    }

    @JvmStatic
    fun exportAudioServer(context: Context): String? {
        return HalTransportProbe.execute(
            context,
            "export-audioserver",
            UUID.randomUUID().toString(),
        )
    }

    @JvmStatic
    fun policyInput(context: Context, routeOnly: Boolean): String? {
        return HalTransportProbe.execute(
            context,
            if (routeOnly) "policy-route" else "policy-input",
            UUID.randomUUID().toString(),
        )
    }

    @JvmStatic
    fun stop(context: Context, nonce: String?): String? {
        if (!AudioServiceRecovery.validNonce(nonce)) return "HAL_PROBE_REFUSED"
        return HalTransportProbe.execute(context, "stop", nonce)
    }

    @JvmStatic
    private fun execute(context: Context, operation: String?, nonce: String?): String? {
        if (BridgeState.capture != null || BridgeState.connected || BridgeState.volume != 0)
            return "HAL_PROBE_REFUSED"
        try {
            val apk = context.getApplicationInfo().sourceDir
            if (!apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex()))
                return "HAL_PROBE_REFUSED"
            return ("nonce=" +
                nonce +
                " " +
                AudioServiceRecovery.run(
                        List.of<String?>(
                            "su",
                            "-c",
                            "CLASSPATH='" +
                                apk +
                                "' exec timeout -k 2 20 app_process /system/bin" +
                                " dev.ryuya.homepodbridge.halprobe.HalPortProbe " +
                                operation +
                                " " +
                                nonce +
                                " 44100",
                        ),
                        16384,
                        24,
                        true,
                    )!!
                    .trim { it <= ' ' })
        } catch (unavailable: Exception) {
            return "nonce=" + nonce + " HAL_PROBE_UNCONFIRMED"
        }
    }
}
