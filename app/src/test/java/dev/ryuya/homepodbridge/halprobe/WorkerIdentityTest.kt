package dev.ryuya.homepodbridge.halprobe

import java.util.Arrays
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test

class WorkerIdentityTest {
    @Test
    fun authenticatedWorkerMustKeepItsPidParentAndStartTime() {
        val identity = WorkerIdentityTest.identity()
        assertTrue(
            identity!!.matches(
                WorkerIdentityTest.stat(123, 99, 456)!!,
                WorkerIdentityTest.ROOT,
                WorkerIdentityTest.COMMAND,
            )
        )
        assertFalse(
            identity!!.matches(
                WorkerIdentityTest.stat(124, 99, 456)!!,
                WorkerIdentityTest.ROOT,
                WorkerIdentityTest.COMMAND,
            )
        )
        assertFalse(
            identity!!.matches(
                WorkerIdentityTest.stat(123, 98, 456)!!,
                WorkerIdentityTest.ROOT,
                WorkerIdentityTest.COMMAND,
            )
        )
        assertFalse(
            identity!!.matches(
                WorkerIdentityTest.stat(123, 99, 457)!!,
                WorkerIdentityTest.ROOT,
                WorkerIdentityTest.COMMAND,
            )
        )
        assertFalse(
            identity!!.matches("123 broken", WorkerIdentityTest.ROOT, WorkerIdentityTest.COMMAND)
        )
    }

    @Test
    fun systemUiHalAndOtherCommandsCanNeverReceiveSignal() {
        val identity = WorkerIdentityTest.identity()
        assertFalse(
            identity!!.matches(
                WorkerIdentityTest.stat(123, 99, 456)!!,
                WorkerIdentityTest.ROOT.replace("0\t0\t0\t0", "1000\t1000\t1000\t1000"),
                WorkerIdentityTest.COMMAND,
            )
        )
        assertFalse(
            identity!!.matches(
                WorkerIdentityTest.stat(123, 99, 456)!!,
                WorkerIdentityTest.ROOT,
                "com.android.systemui\u0000",
            )
        )
        assertFalse(
            identity!!.matches(
                WorkerIdentityTest.stat(123, 99, 456)!!,
                WorkerIdentityTest.ROOT,
                "/vendor/bin/hw/audiohalservice.qti\u0000",
            )
        )
        assertFalse(
            identity!!.matches(
                WorkerIdentityTest.stat(123, 99, 456)!!,
                WorkerIdentityTest.ROOT,
                WorkerIdentityTest.COMMAND + "extra\u0000",
            )
        )
        assertFalse(
            identity!!.matches(
                WorkerIdentityTest.stat(123, 99, 456)!!,
                WorkerIdentityTest.ROOT,
                WorkerIdentityTest.COMMAND.replace("hang\u0000", "none\u0000"),
            )
        )
        assertFalse(
            identity!!.matches(
                WorkerIdentityTest.stat(123, 99, 456)!!,
                WorkerIdentityTest.ROOT,
                WorkerIdentityTest.COMMAND.replace(
                    WorkerIdentityTest.NONCE,
                    "22345678-1234-5678-1234-567812345678",
                ),
            )
        )
    }

    @Test
    fun initialPeerCredentialsAndCommandAreRequired() {
        try {
            WorkerIdentity.authenticate(
                123,
                2000,
                99,
                WorkerIdentityTest.stat(123, 99, 456)!!,
                WorkerIdentityTest.ROOT,
                WorkerIdentityTest.COMMAND,
                WorkerIdentityTest.NONCE,
                ProbeScenario.HANG,
            )
            fail()
        } catch (expected: SecurityException) {}
        try {
            WorkerIdentity.authenticate(
                123,
                0,
                99,
                WorkerIdentityTest.stat(123, 99, 456)!!,
                WorkerIdentityTest.ROOT,
                WorkerIdentityTest.COMMAND,
                WorkerIdentityTest.NONCE,
                ProbeScenario.CONTROL,
            )
            fail()
        } catch (expected: SecurityException) {}
    }

    companion object {
        const val NONCE: String = "12345678-1234-5678-1234-567812345678"
        const val ROOT: String = "Name:\tapp_process\nUid:\t0\t0\t0\t0\n"
        @JvmField
        val COMMAND: String =
            ("app_process\u0000/system/bin\u0000dev.ryuya.homepodbridge.halprobe.HalQueueMapping\u0000hpb-map-" +
                WorkerIdentityTest.NONCE +
                "\u0000" +
                WorkerIdentityTest.NONCE +
                "\u000099\u0000hang\u0000")

        @JvmStatic
        fun stat(pid: Int, parent: Int, start: Int): String? {
            val fields = arrayOfNulls<String>(20)
            Arrays.fill(fields, "0")
            fields[0] = "S"
            fields[1] = parent.toString()
            fields[19] = start.toString()
            return pid.toString() + " (app process)) " + fields.joinToString(" ")
        }

        @JvmStatic
        fun identity(): WorkerIdentity? {
            return WorkerIdentity.authenticate(
                123,
                0,
                99,
                WorkerIdentityTest.stat(123, 99, 456)!!,
                WorkerIdentityTest.ROOT,
                WorkerIdentityTest.COMMAND,
                WorkerIdentityTest.NONCE,
                ProbeScenario.HANG,
            )
        }
    }
}
