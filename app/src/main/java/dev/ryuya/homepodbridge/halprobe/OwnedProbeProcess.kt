package dev.ryuya.homepodbridge.halprobe

import java.util.UUID
import kotlin.jvm.JvmStatic

object OwnedProbeProcess {
    @JvmStatic
    fun matches(command: String, status: String, nonce: String?): Boolean {
        try {
            if (UUID.fromString(nonce).toString() != nonce) return false
        } catch (invalid: RuntimeException) {
            return false
        }
        if (!status.matches("(?s).*\\nUid:\\s+0\\s+0\\s+0\\s+0\\s*\\n.*".toRegex())) return false
        for (scenario in ProbeScenario.entries) {
            val suffix: String =
                ("dev.ryuya.homepodbridge.halprobe.HalPortProbe\u0000" +
                    scenario.operation +
                    "\u0000" +
                    nonce +
                    "\u000044100\u0000")
            if (command.endsWith(suffix)) return true
        }
        return OwnedProbeProcess.matchesClosedCycle(command, nonce)
    }

    @JvmStatic
    fun matchesClosedCycle(command: String, nonce: String?): Boolean {
        return command.endsWith(
            ("dev.ryuya.homepodbridge.halprobe.HalCaptureHost\u0000" +
                nonce +
                "\u0000test-cycle\u0000com.apple.android.music\u0000")
        )
    }
}
