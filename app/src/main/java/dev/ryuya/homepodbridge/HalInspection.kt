package dev.ryuya.homepodbridge

import android.content.Context
import android.media.AudioManager
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized
import kotlin.math.min

/* Explicit, read-only inspection of a fixed set of public HAL implementation binaries. */
object HalInspection {
    private val FILES: Array<String?>? =
        arrayOf<String?>(
            "/vendor/lib64/libaudioserviceexampleimpl.so",
            "/vendor/lib64/libnbaio_mono.so",
            "/vendor/lib64/hw/audio.r_submix.default.so",
        )
    private val MAX_BYTES: Int = 16 * 1024 * 1024
    private var status: String = "Not requested"
    private var image: ByteArray? = null
    private var selected: Int = -1
    private var running: Boolean = false

    @JvmStatic
    @Throws(IOException::class, InterruptedException::class)
    fun operateRoute(context: Context, address: String?, operation: Int): String? {
        if (
            address == null ||
                !address.matches("[0-9]+:ap:[0-9]+mixp:[0-9]+".toRegex()) ||
                operation < 0 ||
                operation > 7
        )
            return "Owned registration unavailable"
        if (
            operation != 0 &&
                (context.getSystemService<AudioManager?>(AudioManager::class.java).getMode() !=
                    AudioManager.MODE_NORMAL)
        )
            return "Device buffer operation skipped during communication"
        val apk = context.getApplicationInfo().sourceDir
        if (!apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex()))
            return "Module path unavailable"
        val action = HalInspection.action(operation)
        val command: String =
            ("CLASSPATH='" +
                apk +
                "' exec app_process /system/bin dev.ryuya.homepodbridge.HalRootProbe '" +
                address +
                "'" +
                action)
        return String(HalInspection.readCommand(command, true)!!, StandardCharsets.UTF_8)
    }

    @JvmStatic
    @Synchronized
    fun requestRoute(context: Context, registration: String?) {
        HalInspection.requestRoute(context, registration, 0)
    }

    @JvmStatic
    @Synchronized
    fun requestRoute(
        context: Context,
        registration: String?,
        freeze: Boolean,
    ) {
        HalInspection.requestRoute(context, registration, if (freeze) 1 else 0)
    }

    @JvmStatic
    @Synchronized
    fun result(): String? {
        return if (HalInspection.running) null else HalInspection.status
    }

    @JvmStatic
    @Synchronized
    fun requestRoute(
        context: Context,
        registration: String?,
        operation: Int,
    ) {
        if (operation < 0 || operation > 7) return
        if (HalInspection.running) return
        if (
            operation != 0 &&
                (context.getSystemService<AudioManager?>(AudioManager::class.java).getMode() !=
                    AudioManager.MODE_NORMAL)
        ) {
            HalInspection.status =
                "Device buffer operation skipped during communication; writeAttempts=0"
            return
        }
        val address: String = if (registration == null) "none" else registration
        if (address != "none" && !address.matches("[0-9]+:ap:[0-9]+mixp:[0-9]+".toRegex())) {
            HalInspection.status = "Unrecognized owned registration"
            return
        }
        val apk = context.getApplicationInfo().sourceDir
        if (!apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex())) {
            HalInspection.status = "Unrecognized module path"
            return
        }
        HalInspection.running = true
        HalInspection.image = null
        HalInspection.selected = 3
        HalInspection.status = "Checking owned HAL route"
        val action = HalInspection.action(operation)
        Thread(
                Runnable {
                    try {
                        val report =
                            HalInspection.readCommand(
                                ("CLASSPATH='" +
                                    apk +
                                    "' exec app_process /system/bin dev.ryuya.homepodbridge.HalRootProbe" +
                                    " '" +
                                    address +
                                    "'" +
                                    action),
                                true,
                            )
                        synchronized(HalInspection::class.java) {
                            HalInspection.image = report
                            HalInspection.status = String(report!!, StandardCharsets.UTF_8)
                        }
                    } catch (error: Exception) {
                        synchronized(HalInspection::class.java) {
                            HalInspection.status =
                                "Route inspection unavailable: " + error.javaClass.getSimpleName()
                        }
                    } finally {
                        synchronized(HalInspection::class.java) {
                            HalInspection.running = false
                        }
                    }
                },
                "HomePod-route-inspection",
            )
            .start()
    }

    @JvmStatic
    @Synchronized
    fun request(index: Int) {
        if (HalInspection.running || index < 0 || index >= HalInspection.FILES!!.size) return
        HalInspection.running = true
        HalInspection.selected = index
        HalInspection.image = null
        HalInspection.status = "Inspecting " + HalInspection.FILES[index]
        Thread(
                Runnable {
                    try {
                        val bytes = HalInspection.read(HalInspection.FILES[index])
                        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                        val hex = StringBuilder()
                        for (value in digest) hex.append(
                            String.format("%02x", value.toInt() and 255)
                        )
                        synchronized(HalInspection::class.java) {
                            HalInspection.image = bytes
                            HalInspection.status =
                                "Read-only ELF bytes=" + bytes!!.size + " sha256=" + hex
                        }
                    } catch (error: Exception) {
                        synchronized(HalInspection::class.java) {
                            HalInspection.status =
                                "Inspection unavailable: " + error.javaClass.getSimpleName()
                        }
                    } finally {
                        synchronized(HalInspection::class.java) {
                            HalInspection.running = false
                        }
                    }
                },
                "HomePod-HAL-inspection",
            )
            .start()
    }

    @JvmStatic
    private fun action(operation: Int): String? {
        return arrayOf<String>(
            "",
            " freeze",
            " tune",
            " restore",
            " brief",
            " restore-brief",
            " minimum",
            " restore-minimum",
        )[operation]
    }

    @JvmStatic
    @Throws(IOException::class, InterruptedException::class)
    private fun read(file: String?): ByteArray? {
        val result = readCommand("exec cat " + file)
        if (
            result!!.size < 64 ||
                result!![0].toInt() != 127 ||
                result!![1] != 'E'.code.toByte() ||
                result!![2] != 'L'.code.toByte() ||
                result!![3] != 'F'.code.toByte()
        )
            throw IOException("Not ELF")
        return result
    }

    @JvmStatic
    @Throws(IOException::class, InterruptedException::class)
    private fun readCommand(command: String?, partial: Boolean = false): ByteArray? {
        val helper = ProcessBuilder("su", "-c", command).start()
        try {
            helper.getInputStream().use { output ->
                helper.getErrorStream().use { errors ->
                    ByteArrayOutputStream().use { bytes ->
                        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        val block = ByteArray(16384)
                        while (helper.isAlive() || output.available() > 0) {
                            if (System.nanoTime() >= deadline)
                                throw IOException("Inspection timed out")
                            val available = output.available()
                            if (available > 0) {
                                val count = output.read(block, 0, min(block.size, available))
                                if (count < 0) break
                                if (bytes.size() + count > HalInspection.MAX_BYTES)
                                    throw IOException("Image too large")
                                bytes.write(block, 0, count)
                            } else Thread.sleep(5)
                            // Drain only; diagnostics never include root prompts or arbitrary
                            // stderr contents.
                            while (errors.available() > 0) errors.read(
                                block,
                                0,
                                min(block.size, errors.available()),
                            )
                        }
                        if (!helper.waitFor(1, TimeUnit.SECONDS) || helper.exitValue() != 0) {
                            if (!partial) throw IOException("Read unavailable")
                            bytes.write(
                                "\nRoot helper exited without confirming the operation"
                                    .toByteArray(StandardCharsets.UTF_8)
                            )
                        }
                        return bytes.toByteArray()
                    }
                }
            }
        } finally {
            helper.destroy()
        }
    }

    @JvmStatic
    @Synchronized
    fun dump(writer: PrintWriter, includeImage: Boolean) {
        writer.println(
            "HAL inspection index=" +
                HalInspection.selected +
                " running=" +
                HalInspection.running +
                " " +
                HalInspection.status
        )
        if (includeImage && HalInspection.image != null) {
            writer.println("HOMEPOD_HAL_IMAGE_BEGIN")
            writer.println(Base64.encodeToString(HalInspection.image, Base64.NO_WRAP))
            writer.println("HOMEPOD_HAL_IMAGE_END")
        }
    }
}
