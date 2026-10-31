package dev.ryuya.homepodbridge.halprobe

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.min

/* Android Process.destroyForcibly can close its pipes: collect available evidence first. */
object WorkerTermination {
    @JvmStatic
    @Throws(Exception::class)
    fun collect(
        child: Process,
        readWaitNanos: Long,
        killer: WorkerTermination.Killer,
    ): WorkerTermination.Outcome? {
        val forced: Boolean = !child.waitFor(1, TimeUnit.SECONDS)
        var diagnostics: String = ""
        try {
            diagnostics =
                WorkerTermination.available(child.getInputStream()) +
                    WorkerTermination.available(child.getErrorStream())
        } catch (unavailable: Exception) {
            diagnostics = "WORKER_DIAGNOSTICS_UNAVAILABLE"
        } finally {
            if (forced) killer.kill()
        }
        if (!child.waitFor(1, TimeUnit.SECONDS)) throw IOException("Worker termination unconfirmed")
        return WorkerTermination.Outcome(
            child.exitValue(),
            forced,
            forced && readWaitNanos >= TimeUnit.SECONDS.toNanos(3),
            diagnostics,
        )
    }

    @JvmStatic
    @Throws(IOException::class)
    private fun available(stream: InputStream): String? {
        val count = min(1024, stream.available())
        return if (count == 0) "" else String(stream.readNBytes(count), StandardCharsets.UTF_8)
    }

    @JvmRecord
    data class Outcome(
        val exitCode: Int,
        val forced: Boolean,
        val timedOut: Boolean,
        val diagnostics: String?,
    ) {
        override fun toString(): String =
            "Outcome[exitCode=$exitCode, forced=$forced, timedOut=$timedOut, diagnostics=$diagnostics]"
    }

    fun interface Killer {
        @Throws(Exception::class) fun kill()
    }
}
