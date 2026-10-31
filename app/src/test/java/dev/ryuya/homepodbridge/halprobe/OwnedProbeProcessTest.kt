package dev.ryuya.homepodbridge.halprobe

import org.junit.Assert.*
import org.junit.Test

class OwnedProbeProcessTest {
    @Test
    fun helperCleanupCannotSelectSystemUiHalOrAnotherNonce() {
        val nonce: String = "12345678-1234-5678-1234-567812345678"
        val command: String =
            ("app_process\u0000/system/bin\u0000dev.ryuya.homepodbridge.halprobe.HalPortProbe\u0000streams\u0000" +
                nonce +
                "\u000044100\u0000")
        val root: String = "Name:\tapp_process\nUid:\t0\t0\t0\t0\n"
        assertTrue(OwnedProbeProcess.matches(command, root, nonce))
        assertFalse(OwnedProbeProcess.matches("com.android.systemui\u0000", root, nonce))
        assertFalse(
            OwnedProbeProcess.matches("/vendor/bin/hw/audiohalservice.qti\u0000", root, nonce)
        )
        assertFalse(
            OwnedProbeProcess.matches(
                command.replace(nonce, "22345678-1234-5678-1234-567812345678"),
                root,
                nonce,
            )
        )
        assertFalse(
            OwnedProbeProcess.matches(
                command,
                root.replace("0\t0\t0\t0", "2000\t2000\t2000\t2000"),
                nonce,
            )
        )
        assertFalse(OwnedProbeProcess.matches(command + "extra\u0000", root, nonce))
        assertFalse(OwnedProbeProcess.matches(command, root, nonce + "; id"))
        val cycle: String =
            ("app_process\u0000/system/bin\u0000dev.ryuya.homepodbridge.halprobe.HalCaptureHost\u0000" +
                nonce +
                "\u0000test-cycle\u0000com.apple.android.music\u0000")
        assertTrue(OwnedProbeProcess.matches(cycle, root, nonce))
        assertFalse(OwnedProbeProcess.matches(cycle.replace("test-cycle", "whole"), root, nonce))
        assertFalse(
            OwnedProbeProcess.matches(
                cycle.replace("com.apple.android.music", "other.app"),
                root,
                nonce,
            )
        )
    }
}
