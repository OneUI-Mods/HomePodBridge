package dev.ryuya.homepodbridge

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.JvmField
import kotlin.math.min

/*
 * Private bounded zero-only HAL handoff. Closing stdout requests cleanup; never kills its owner.
 */
class RootZeroInput(context: Context) : AutoCloseable {
    @JvmField val nonce: String? = UUID.randomUUID().toString()
    private val process: Process
    private val closed: AtomicBoolean = AtomicBoolean()

    init {
        if (BridgeState.connected || BridgeState.volume != 0)
            throw IOException("Muted idle required")
        val apk = context.getApplicationInfo().sourceDir
        if (!apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex()))
            throw SecurityException()
        context.deleteFile("last-hal-zero.txt")
        process =
            ProcessBuilder(
                    "su",
                    "-c",
                    ("CLASSPATH='" +
                        apk +
                        "' exec timeout -k 2 30 app_process /system/bin" +
                        " dev.ryuya.homepodbridge.halprobe.HalPortProbe policy-forward " +
                        nonce +
                        " 44100"),
                )
                .start()
        val app = context.getApplicationContext()
        val diagnostics =
            Thread(
                Runnable {
                    try {
                        process.getErrorStream().use { errors ->
                            val kept = ByteArrayOutputStream()
                            val block = ByteArray(1024)
                            var count: Int
                            while ((errors.read(block).also { count = it }) != -1) {
                                val remaining: Int = 8192 - kept.size()
                                if (remaining > 0) kept.write(block, 0, min(remaining, count))
                            }
                            app.openFileOutput("last-hal-zero.txt", Context.MODE_PRIVATE).use {
                                output ->
                                output.write(
                                    ("nonce=" + nonce + "\n").toByteArray(StandardCharsets.US_ASCII)
                                )
                                output.write(kept.toByteArray())
                            }
                        }
                    } catch (ignored: IOException) {
                        // No PCM is ever read from or written to the diagnostic log.
                    } finally {
                        try {
                            process.getOutputStream().close()
                        } catch (ignored: IOException) {}
                    }
                },
                "HomePod-zero-HAL-result",
            )
        diagnostics.setDaemon(true)
        diagnostics.start()
    }

    fun input(): InputStream? {
        return process.getInputStream()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            process.getOutputStream().close()
        } catch (ignored: IOException) {}
        val closer =
            Thread(
                Runnable {
                    try {
                        process.getInputStream().close()
                    } catch (ignored: IOException) {}
                },
                "HomePod-zero-HAL-close",
            )
        closer.setDaemon(true)
        closer.start()
        // EPIPE reaches the isolated writer. The Java owner closes its Binders and owned IDs.
    }
}
