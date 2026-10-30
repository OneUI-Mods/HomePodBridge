package dev.ryuya.homepodbridge.halprobe

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmField
import kotlin.jvm.Synchronized
import org.junit.Assert.*
import org.junit.Test

class WorkerTerminationTest {
    class Pipe(data: String) : ByteArrayInputStream(data.toByteArray(StandardCharsets.UTF_8)) {
        @JvmField var closed: Boolean = false

        @Synchronized
        override fun available(): Int {
            check(!closed) { "Stream closed" }
            return super.available()
        }

        override fun close() {
            closed = true
        }
    }

    class Child : Process() {
        @JvmField
        val out: WorkerTerminationTest.Pipe = WorkerTerminationTest.Pipe("FAULT_CHECKPOINT=hang\n")
        @JvmField val err: WorkerTerminationTest.Pipe = WorkerTerminationTest.Pipe("")
        @JvmField var alive: Boolean = true
        @JvmField var neverStops: Boolean = false
        @JvmField var code: Int = 137

        override fun getOutputStream(): OutputStream? {
            return OutputStream.nullOutputStream()
        }

        override fun getInputStream(): InputStream {
            return out
        }

        override fun getErrorStream(): InputStream {
            return err
        }

        override fun waitFor(): Int {
            return code
        }

        override fun waitFor(timeout: Long, unit: TimeUnit?): Boolean {
            return !alive
        }

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException()
            return code
        }

        override fun destroy() {
            destroyForcibly()
        }

        override fun destroyForcibly(): Process? {
            out.close()
            err.close()
            if (!neverStops) alive = false
            return this
        }

        override fun isAlive(): Boolean {
            return alive
        }
    }

    @Test
    @Throws(Exception::class)
    fun capturesCheckpointBeforeAndroidClosesPipesOnForcedExit() {
        val child = WorkerTerminationTest.Child()
        val outcome =
            WorkerTermination.collect(
                child,
                TimeUnit.SECONDS.toNanos(3),
                WorkerTermination.Killer { child.destroyForcibly() },
            )
        assertTrue(child.out.closed)
        assertTrue(outcome!!.forced)
        assertTrue(outcome!!.timedOut)
        assertEquals("FAULT_CHECKPOINT=hang\n", outcome!!.diagnostics)
        assertTrue(
            ProbeScenario.HANG.acceptsFailure(
                outcome!!.exitCode,
                outcome!!.timedOut,
                outcome!!.forced,
                outcome!!.diagnostics,
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun earlyReadFailureCannotBeCalledTimeout() {
        val child = WorkerTerminationTest.Child()
        val outcome =
            WorkerTermination.collect(
                child,
                TimeUnit.MILLISECONDS.toNanos(200),
                WorkerTermination.Killer { child.destroyForcibly() },
            )
        assertFalse(outcome!!.timedOut)
        assertFalse(
            ProbeScenario.HANG.acceptsFailure(
                outcome!!.exitCode,
                outcome!!.timedOut,
                outcome!!.forced,
                outcome!!.diagnostics,
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun naturalExitIsNeverClassifiedAsForcedHang() {
        val child = WorkerTerminationTest.Child()
        child.alive = false
        child.code = 73
        val outcome =
            WorkerTermination.collect(
                child,
                TimeUnit.SECONDS.toNanos(4),
                WorkerTermination.Killer { child.destroyForcibly() },
            )
        assertFalse(outcome!!.forced)
        assertFalse(outcome!!.timedOut)
        assertFalse(child.out.closed)
        assertFalse(
            ProbeScenario.HANG.acceptsFailure(
                outcome!!.exitCode,
                outcome!!.timedOut,
                outcome!!.forced,
                outcome!!.diagnostics,
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun missingPipesStillRequireConfirmedTerminationAndCannotPassFault() {
        val child = WorkerTerminationTest.Child()
        child.out.close()
        val outcome =
            WorkerTermination.collect(
                child,
                TimeUnit.SECONDS.toNanos(3),
                WorkerTermination.Killer { child.destroyForcibly() },
            )
        assertFalse(child.alive)
        assertTrue(outcome!!.forced)
        assertEquals("WORKER_DIAGNOSTICS_UNAVAILABLE", outcome!!.diagnostics)
        assertFalse(
            ProbeScenario.HANG.acceptsFailure(
                outcome!!.exitCode,
                outcome!!.timedOut,
                outcome!!.forced,
                outcome!!.diagnostics,
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun unconfirmedTerminationIsAnError() {
        val child = WorkerTerminationTest.Child()
        child.neverStops = true
        try {
            WorkerTermination.collect(
                child,
                TimeUnit.SECONDS.toNanos(3),
                WorkerTermination.Killer { child.destroyForcibly() },
            )
            fail()
        } catch (expected: IOException) {
            assertEquals("Worker termination unconfirmed", expected.message)
        }
    }
}
