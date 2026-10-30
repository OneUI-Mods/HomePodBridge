package dev.ryuya.homepodbridge

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.ryuya.homepodbridge.halprobe.PhoneTiming
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.SequenceInputStream
import java.lang.ProcessBuilder.Redirect
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.LongSupplier
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import org.json.JSONObject

/* App side of a root-owned playback policy. LocalSocket close is immediate and cancels capture. */
class RootHalInput
private constructor(
    context: Context,
    source: String,
    whole: Boolean,
    zero: Boolean,
    pulse: Boolean,
    testTarget: Int,
    shortPeriod: Boolean,
    timing: PhoneTiming? = if (shortPeriod) PhoneTiming.fastest() else null,
) : AutoCloseable {
    class StartupException(cause: Throwable?, safe: Boolean) :
        IOException("Dedicated capture unavailable", cause) {
        @JvmField val safeFallback: Boolean

        init {
            safeFallback = safe
        }
    }

    @JvmField val nonce: String? = UUID.randomUUID().toString()
    private var process: Process? = null
    private var socket: LocalSocket? = null
    private var data: InputStream? = null
    private val finished: CountDownLatch = CountDownLatch(1)
    @Volatile private var result: JSONObject? = null
    @JvmField var registration: String? = null

    @JvmOverloads
    constructor(
        context: Context,
        source: String,
        whole: Boolean,
        zero: Boolean,
        pulse: Boolean,
        testTarget: Int = 800,
    ) : this(context, source, whole, zero, pulse, testTarget, false)

    constructor(
        context: Context,
        source: String,
        whole: Boolean,
        zero: Boolean,
        pulse: Boolean,
        shortPeriod: Boolean,
    ) : this(context, source, whole, zero, pulse, 800, shortPeriod)

    constructor(
        context: Context,
        source: String,
        whole: Boolean,
        zero: Boolean,
        pulse: Boolean,
        timing: PhoneTiming?,
    ) : this(context, source, whole, zero, pulse, 800, timing != null, timing!!)

    init {
        try {
            if (
                testTarget != 800 &&
                    ((testTarget != 752 && testTarget != 768) || !zero || whole || pulse)
            )
                throw SecurityException(
                    "Only fixed self-UID zero tests may change the candidate reserve"
                )
            if (
                BridgeState.connected ||
                    BridgeState.volume != 0 ||
                    !source.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+".toRegex())
            )
                throw SecurityException()
            val apk = context.getApplicationInfo().sourceDir
            if (!apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex()))
                throw SecurityException()
            var mode: String? =
                if (zero)
                    (if (pulse) "test-cycle" else if (whole) "test-whole" else "test-selected")
                else if (whole) "whole" else "selected"
            if (testTarget != 800) mode = "test-target-" + testTarget
            if (shortPeriod) {
                if (testTarget != 800) throw SecurityException()
                mode = mode + "-short"
            }
            LocalServerSocket("hpb-live-" + nonce).use { server ->
                process =
                    ProcessBuilder(
                            "su",
                            "-c",
                            ("CLASSPATH='" +
                                apk +
                                "' exec app_process /system/bin " +
                                "dev.ryuya.homepodbridge.halprobe.HalCaptureHost " +
                                nonce +
                                " " +
                                mode +
                                " " +
                                source +
                                (if (shortPeriod)
                                    " " + timing!!.periodMs + " " + timing!!.reserveFrames
                                else "")),
                        )
                        .redirectError(Redirect.to(File("/dev/null")))
                        .start()
                val app = context.getApplicationContext()
                val monitor =
                    Thread(
                        Runnable {
                            try {
                                BufferedReader(
                                        InputStreamReader(
                                            process!!.getInputStream(),
                                            StandardCharsets.UTF_8,
                                        )
                                    )
                                    .use { input ->
                                        var line: String? = null
                                        while ((input.readLine().also { line = it }) != null) if (
                                            line!!.startsWith("{") && line!!.length <= 16384
                                        ) {
                                            result = JSONObject(line)
                                            finished.countDown()
                                            app.openFileOutput(
                                                    "last-hal-capture.txt",
                                                    Context.MODE_PRIVATE,
                                                )
                                                .use { output ->
                                                    output.write(
                                                        ("nonce=" + nonce + "\n" + line)
                                                            .toByteArray(StandardCharsets.UTF_8)
                                                    )
                                                }
                                        }
                                    }
                            } catch (ignored: Exception) {} finally {
                                finished.countDown()
                            }
                        },
                        "HomePod-HAL-result",
                    )
                monitor.setDaemon(true)
                monitor.start()
                val poll = StructPollfd()
                poll.fd = server.getFileDescriptor()
                poll.events = OsConstants.POLLIN.toShort()
                val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                while (System.nanoTime() < deadline && process!!.isAlive()) {
                    Os.poll(arrayOf<StructPollfd?>(poll), 100)
                    if ((poll.revents.toInt() and OsConstants.POLLIN) != 0) break
                }
                if ((poll.revents.toInt() and OsConstants.POLLIN) == 0)
                    throw IOException("No HAL host")
                socket = server.accept()
                socket!!.setSoTimeout(if (shortPeriod) 16000 else 8000)
                if (socket!!.getPeerCredentials().getUid() != 0)
                    throw SecurityException("Root host required")
                val input = DataInputStream(socket!!.getInputStream())
                if (input.readInt() != 0x48505231 || input.readUTF() != nonce)
                    throw SecurityException("Capture handshake mismatch")
                registration = input.readUTF()
                if (!registration!!.matches("[0-9]+:ap:[0-9]+mixp:[0-9]+".toRegex()))
                    throw SecurityException()
                val header = ByteArray(24)
                input.readFully(header)
                val payload = ByteBuffer.wrap(header).getInt(4)
                if (payload != 88 && payload != 176) throw IOException("Invalid HAL block")
                val first = ByteArray(24 + payload)
                System.arraycopy(header, 0, first, 0, header.size)
                input.readFully(first, 24, payload)
                // Decode it before accepting the backend. The first record may represent idle
                // silence.
                PcmPipe(
                        Channels.newChannel(ByteArrayInputStream(first)),
                        true,
                        LongSupplier { System.nanoTime() },
                        PcmPipe.HAL_MAGIC,
                    )
                    .read(ByteBuffer.allocateDirect(176))
                socket!!.setSoTimeout(3000)
                data =
                    SequenceInputStream(
                        ByteArrayInputStream(first),
                        socket!!.getInputStream(),
                    )
            }
        } catch (unavailable: Throwable) {
            close()
            val safe = awaitCleanup(5000)
            throw RootHalInput.StartupException(unavailable, safe)
        }
    }

    fun input(): InputStream? {
        return data
    }

    fun awaitCleanup(milliseconds: Long): Boolean {
        if (process == null) return true
        try {
            finished.await(milliseconds, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val report: JSONObject? = result
        return report != null &&
            report.optBoolean("periodCleanupConfirmed", true) &&
            (report.optBoolean("resourcesRestoredExactly") ||
                report.optBoolean("ownedResourcesReleased"))
    }

    override fun close() {
        try {
            if (socket != null) socket!!.close()
        } catch (ignored: IOException) {}
        try {
            if (process != null) process!!.getOutputStream().close()
        } catch (ignored: IOException) {}
    }
}
