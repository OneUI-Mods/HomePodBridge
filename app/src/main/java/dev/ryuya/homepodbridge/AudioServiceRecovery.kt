package dev.ryuya.homepodbridge

import android.content.Context
import java.io.File
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.List
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import org.json.JSONObject

/* Explicit, DUMP-protected recovery of a stranded, unstarted prototype route. */
object AudioServiceRecovery {
    @JvmStatic
    fun validNonce(value: String?): Boolean {
        try {
            return value != null && UUID.fromString(value).toString() == value
        } catch (invalid: IllegalArgumentException) {
            return false
        }
    }

    @JvmStatic
    fun onlyUnstartedOwnedRoute(dump: String, nonce: String): Boolean {
        if (!AudioServiceRecovery.validNonce(nonce)) return false
        val expected: String =
            ("SubmixRoutes:\n - AudioDeviceAddress{id: HomePodBridgeDirect:" +
                nonce +
                "}: Input closed, standby, refcount: 0, framesRead: 0;" +
                " Output open, standby, framesWritten: 0")
        return dump.replace("\r", "").trim { it <= ' ' } == expected
    }

    @JvmStatic
    fun onlyOwnedZeroRoute(dump: String, nonce: String?): Boolean {
        if (!AudioServiceRecovery.validNonce(nonce)) return false
        val prefix =
            Pattern.quote(
                "SubmixRoutes:\n - AudioDeviceAddress{id: HomePodBridgeDirect:" +
                    nonce +
                    "}: Input "
            )
        return dump
            .replace("\r", "")
            .trim { it <= ' ' }
            .matches(
                (prefix +
                        "(?:closed, (?:standby|active), refcount: 0|open, (?:standby|active), refcount:" +
                        " 1), framesRead: [0-9]+; Output (?:open|closed), (?:standby|active)," +
                        " framesWritten: [0-9]+")
                    .toRegex()
            )
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun ownsZeroJournal(nonce: String?, pid: String?, stat: String): Boolean {
        val journal = Path.of("/data/local/tmp/homepod-direct-" + nonce + ".json")
        if (
            Files.isSymbolicLink(journal) ||
                Files.size(journal) > 65536 ||
                ((Files.getAttribute(
                        journal,
                        "unix:uid",
                        LinkOption.NOFOLLOW_LINKS,
                    ) as Number)
                    .toInt() != 0)
        )
            return false
        val saved = JSONObject(AudioServiceRecovery.readProc(journal))
        val start: String? =
            TextCompatibility.split(
                    stat.substring(stat.lastIndexOf(')') + 2),
                    "\\s+",
                )[19]
        return saved.optBoolean("zeroOnly", false) &&
            (pid + ":" + start) == saved.getString("epoch")
    }

    @JvmStatic
    fun restart(context: Context, nonce: String?): String? {
        if (
            !AudioServiceRecovery.validNonce(nonce) ||
                BridgeState.connected ||
                BridgeState.volume != 0
        )
            return "RECOVERY_REFUSED"
        try {
            val apk = context.getApplicationInfo().sourceDir
            if (!apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex()))
                return "RECOVERY_REFUSED"
            return run(
                    List.of<String?>(
                        "su",
                        "-c",
                        ("CLASSPATH='" +
                            apk +
                            "' exec app_process /system/bin" +
                            " dev.ryuya.homepodbridge.AudioServiceRecovery " +
                            nonce),
                    ),
                    256,
                    20,
                )!!
                .trim { it <= ' ' }
        } catch (unavailable: Exception) {
            return "RECOVERY_UNAVAILABLE"
        }
    }

    @JvmStatic
    @JvmOverloads
    @Throws(Exception::class)
    fun run(
        args: MutableList<String?>?,
        maximum: Int,
        seconds: Int,
        retainFailure: Boolean = false,
    ): String? {
        val process = ProcessBuilder(args).redirectError(Redirect.to(File("/dev/null"))).start()
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val timeout =
            watchdog.schedule<Process?>(
                Callable { process.destroyForcibly() },
                seconds.toLong(),
                TimeUnit.SECONDS,
            )
        try {
            val output = process.getInputStream().readNBytes(maximum + 1)
            if (
                output.size > maximum ||
                    !process.waitFor(seconds.toLong(), TimeUnit.SECONDS) ||
                    (!retainFailure && process.exitValue() != 0)
            )
                throw IOException("Recovery command unavailable")
            return String(output, StandardCharsets.UTF_8)
        } finally {
            timeout.cancel(false)
            watchdog.shutdownNow()
            process.destroy()
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    private fun readProc(path: Path?): String? {
        return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun modulePid(): String? {
        val pid =
            run(
                    java.util.List.of<String?>(
                        "dumpsys",
                        "--pid",
                        "android.hardware.audio.core.IModule/r_submix",
                    ),
                    128,
                    3,
                )!!
                .trim { it <= ' ' }
        if (!pid.matches("[1-9][0-9]{0,6}".toRegex())) throw IOException("No module process")
        return pid
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(!(args.size != 1 || !AudioServiceRecovery.validNonce(args[0])))
            val pid = AudioServiceRecovery.modulePid()
            val proc = Path.of("/proc", pid)
            val executable = AudioServiceRecovery.readProc(proc.resolve("cmdline"))
            val identity = AudioServiceRecovery.readProc(proc.resolve("status"))
            if (
                TextCompatibility.split(executable!!, "\u0000")[0] !=
                    "/vendor/bin/hw/audiohalservice.qti" ||
                    !identity!!.matches(
                        "(?s).*\\nUid:\\s+1041\\s+1041\\s+1041\\s+1041\\s*\\n.*".toRegex()
                    )
            )
                throw SecurityException()
            val epoch = AudioServiceRecovery.readProc(proc.resolve("stat"))
            val mode = run(java.util.List.of<String?>("dumpsys", "audio"), 1048576, 4)
            if (!mode!!.contains("mAudioModeOwner: AudioDeviceBroker\$AudioModeInfo[mMode=0,"))
                throw SecurityException()
            val routes =
                run(
                    java.util.List.of<String?>(
                        "dumpsys",
                        "android.hardware.audio.core.IModule/r_submix",
                    ),
                    8192,
                    3,
                )
            if (
                !AudioServiceRecovery.onlyUnstartedOwnedRoute(routes!!, args[0]) &&
                    !(AudioServiceRecovery.onlyOwnedZeroRoute(routes!!, args[0]) &&
                        AudioServiceRecovery.ownsZeroJournal(args[0], pid, epoch!!))
            )
                throw SecurityException()
            if (
                ("running" !=
                    run(
                            java.util.List.of<String?>("getprop", "init.svc.vendor.audio-hal-aidl"),
                            128,
                            2,
                        )!!
                        .trim { it <= ' ' }) || pid != AudioServiceRecovery.modulePid()
            )
                throw SecurityException()
            val currentStat = AudioServiceRecovery.readProc(proc.resolve("stat"))
            // PID reuse guard: compare the process start-time field, rather than changing counters.
            if (
                TextCompatibility.split(
                        epoch!!.substring(epoch!!.lastIndexOf(')') + 2),
                        "\\s+",
                    )[19] !=
                    TextCompatibility.split(
                            currentStat!!.substring(currentStat!!.lastIndexOf(')') + 2),
                            "\\s+",
                        )[19]
            )
                throw SecurityException()
            run(
                java.util.List.of<String?>("setprop", "ctl.restart", "vendor.audio-hal-aidl"),
                128,
                2,
            )
            val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (System.nanoTime() < deadline) {
                try {
                    if (
                        pid != AudioServiceRecovery.modulePid() &&
                            run(
                                    java.util.List.of<String?>(
                                        "dumpsys",
                                        "android.hardware.audio.core.IModule/r_submix",
                                    ),
                                    8192,
                                    2,
                                )!!
                                .contains("<Empty>")
                    ) {
                        println("RECOVERY_COMPLETE")
                        return
                    }
                } catch (restarting: Exception) {
                    /* Init temporarily removes the service. */
                }
                Thread.sleep(200)
            }
            println("RECOVERY_NOT_CONFIRMED")
        } catch (refused: Exception) {
            println("RECOVERY_REFUSED")
        }
    }
}
