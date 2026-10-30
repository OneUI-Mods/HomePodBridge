package dev.ryuya.homepodbridge

import org.junit.Assert.*
import org.junit.Test

class AudioServiceRecoveryTest {
    @Test
    fun refusesForeignOrActiveRoutesBeforeSharedServiceRestart() {
        assertTrue(
            AudioServiceRecovery.onlyUnstartedOwnedRoute(
                AudioServiceRecoveryTest.DUMP,
                AudioServiceRecoveryTest.NONCE,
            )
        )
        assertFalse(
            AudioServiceRecovery.onlyUnstartedOwnedRoute(
                AudioServiceRecoveryTest.DUMP + AudioServiceRecoveryTest.DUMP,
                AudioServiceRecoveryTest.NONCE,
            )
        )
        assertFalse(
            AudioServiceRecovery.onlyUnstartedOwnedRoute(
                AudioServiceRecoveryTest.DUMP.replace("framesWritten: 0", "framesWritten: 1"),
                AudioServiceRecoveryTest.NONCE,
            )
        )
        assertFalse(
            AudioServiceRecovery.onlyUnstartedOwnedRoute(
                AudioServiceRecoveryTest.DUMP.replace("Input closed", "Input open"),
                AudioServiceRecoveryTest.NONCE,
            )
        )
        assertFalse(
            AudioServiceRecovery.onlyUnstartedOwnedRoute(
                AudioServiceRecoveryTest.DUMP.replace("HomePodBridgeDirect:", "AnotherApp:"),
                AudioServiceRecoveryTest.NONCE,
            )
        )
        assertFalse(
            AudioServiceRecovery.onlyUnstartedOwnedRoute(
                AudioServiceRecoveryTest.DUMP,
                "22345678-1234-5678-1234-567812345678",
            )
        )
        assertFalse(
            AudioServiceRecovery.onlyUnstartedOwnedRoute(
                "SubmixRoutes:\n<Empty>",
                AudioServiceRecoveryTest.NONCE,
            )
        )
    }

    @Test
    fun nonceCannotSupplyShellTextOrAlternateUuidSpellings() {
        assertFalse(AudioServiceRecovery.validNonce(null))
        assertFalse(AudioServiceRecovery.validNonce(AudioServiceRecoveryTest.NONCE + "; id"))
        assertFalse(AudioServiceRecovery.validNonce("1-1-1-1-1"))
        assertFalse(AudioServiceRecovery.validNonce(""))
    }

    @Test
    fun startedZeroRouteStillRequiresOneExactOwnedAddress() {
        val active =
            AudioServiceRecoveryTest.DUMP.replace(
                    "Input closed, standby, refcount: 0, framesRead: 0",
                    "Input open, active, refcount: 1, framesRead: 88",
                )
                .replace(
                    "Output open, standby, framesWritten: 0",
                    "Output open, active, framesWritten: 88",
                )
        assertTrue(AudioServiceRecovery.onlyOwnedZeroRoute(active, AudioServiceRecoveryTest.NONCE))
        assertFalse(
            AudioServiceRecovery.onlyUnstartedOwnedRoute(active, AudioServiceRecoveryTest.NONCE)
        )
        assertFalse(
            AudioServiceRecovery.onlyOwnedZeroRoute(active + active, AudioServiceRecoveryTest.NONCE)
        )
        assertFalse(
            AudioServiceRecovery.onlyOwnedZeroRoute(
                active.replace("refcount: 1", "refcount: 2"),
                AudioServiceRecoveryTest.NONCE,
            )
        )
        assertFalse(
            AudioServiceRecovery.onlyOwnedZeroRoute(
                active.replace("HomePodBridgeDirect:", "AnotherApp:"),
                AudioServiceRecoveryTest.NONCE,
            )
        )
    }

    companion object {
        private const val NONCE: String = "12345678-1234-5678-1234-567812345678"
        private val DUMP: String =
            ("SubmixRoutes:\n - AudioDeviceAddress{id: HomePodBridgeDirect:" +
                AudioServiceRecoveryTest.NONCE +
                "}: Input closed, standby, refcount: 0, framesRead: 0; Output open, standby," +
                " framesWritten: 0\n")
    }
}
