package dev.ryuya.homepodbridge.halprobe

import android.content.Context
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.ArrayList
import java.util.HexFormat
import java.util.List
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized
import org.json.JSONObject

/*
 * Root-owned virtual-route preparation and zero-only diagnostics; no instruction bytes are patched.
 */
class HalPeriodExperiment
@JvmOverloads
constructor(address: String?, mixer: Boolean, timing: PhoneTiming = PhoneTiming.fastest()) :
    AutoCloseable {
    private val directory: Path
    private val process: Process
    private val control: Thread
    private val ready: CountDownLatch = CountDownLatch(1)
    private val transcript: StringBuilder = StringBuilder()
    @Volatile private var stopping: Boolean = false
    private var closed: Boolean = false
    private var cleanupConfirmed: Boolean = false

    fun cleanupConfirmed(): Boolean {
        return cleanupConfirmed
    }

    @Volatile private var failure: String? = null
    @Volatile private var accepted: Int = 0

    init {
        val apk = System.getenv("CLASSPATH")
        if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex()))
            throw SecurityException("Installed helper required")
        val bytes: ByteArray?
        ZipFile(apk).use { archive ->
            val entry = archive.getEntry("lib/arm64-v8a/libhomepod_period_tool.so")
            if (entry == null || entry.getSize() > 1048576)
                throw SecurityException("Packaged trial tool missing")
            archive.getInputStream(entry).use { input ->
                bytes = input.readNBytes(1048577)
            }
        }
        if (
            bytes!!.size > 1048576 ||
                bytes!!.size < 64 ||
                bytes!![0].toInt() != 127 ||
                bytes!![1] != 'E'.code.toByte() ||
                bytes!![2] != 'L'.code.toByte() ||
                bytes!![3] != 'F'.code.toByte()
        )
            throw SecurityException("Invalid packaged executable")
        if (mixer) {
            val library = Files.readAllBytes(Path.of("/system/bin/audioserver"))
            if (
                library.size > 4194304 ||
                    (HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(library)) !=
                        "78d47cf72768eff61e5e47ad96c5cd10e6779b97301cf01c135bccda05c9e201")
            )
                throw SecurityException("Unsupported mixer build")
        }
        directory =
            Files.createTempDirectory(
                Path.of("/data/local/tmp"),
                "homepod-getter-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        val executable = directory.resolve("probe")
        var started: Process? = null
        try {
            Files.write(executable, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
            val query =
                ProcessBuilder(
                        "dumpsys",
                        "--pid",
                        if (mixer) "media.audio_flinger"
                        else ReadOnlyHalState.DESCRIPTOR + "/r_submix",
                    )
                    .start()
            val pid =
                String(query.getInputStream().readNBytes(128), StandardCharsets.US_ASCII).trim {
                    it <= ' '
                }
            if (!query.waitFor(2, TimeUnit.SECONDS) || !pid.matches("[1-9][0-9]{0,6}".toRegex()))
                throw SecurityException()
            var base: String? = null

            for (line in Files.readAllLines(Path.of("/proc", pid, "maps"))) {
                val fields =
                    line
                        .trim { it <= ' ' }
                        .split("\\s+".toRegex())
                        .dropLastWhile { it.isEmpty() }
                        .toTypedArray()
                if (
                    fields.size == 6 &&
                        fields[2] == "00000000" &&
                        fields[5] ==
                            if (mixer) "/system/bin/audioserver"
                            else "/vendor/lib64/libaudioserviceexampleimpl.so"
                ) {
                    if (base != null) throw SecurityException("Ambiguous module")
                    base =
                        fields[0]
                            .split("-".toRegex())
                            .dropLastWhile { it.isEmpty() }
                            .toTypedArray()[0]
                }
            }
            if (base == null) throw SecurityException("Pinned module missing")
            started =
                ProcessBuilder(
                        List.of<String?>(
                            executable.toString(),
                            pid,
                            base,
                            if (mixer) "mixer" else "hal",
                            timing.periodMs.toString(),
                        )
                    )
                    .redirectErrorStream(true)
                    .start()
        } catch (error: Exception) {
            if (started != null) started.destroyForcibly()
            Files.deleteIfExists(executable)
            Files.deleteIfExists(directory)
            throw error
        }
        process = started
        control =
            Thread(
                Runnable {
                    try {
                        BufferedReader(
                                InputStreamReader(
                                    process.getInputStream(),
                                    StandardCharsets.US_ASCII,
                                )
                            )
                            .use { reader ->
                                var line: String? = null
                                while ((reader.readLine().also { line = it }) != null) {
                                    if (line!!.length > 256)
                                        throw SecurityException("Invalid trace reply")
                                    synchronized(transcript) {
                                        if (transcript.length < 8192)
                                            transcript.append(line).append('\n')
                                    }
                                    if (line!!.startsWith("READY ")) ready.countDown()
                                    if (line!!.startsWith("HIT ")) {
                                        val fields =
                                            line!!
                                                .split(" ".toRegex())
                                                .dropLastWhile { it.isEmpty() }
                                                .toTypedArray()
                                        if (fields.size != 4)
                                            throw SecurityException("Invalid getter event")
                                        val sequence = fields[1].toInt()
                                        val id = fields[2].toInt()
                                        val port = fields[3].toInt()
                                        val allow: Boolean =
                                            !stopping &&
                                                (if (mixer)
                                                    HalPeriodExperiment.ownedMixer(
                                                        ReadOnlyHalState().snapshot()!!,
                                                        address,
                                                        id,
                                                        timing.periodFrames(),
                                                    )
                                                else
                                                    HalPeriodExperiment.ownedConfig(
                                                        ReadOnlyHalState().snapshot()!!,
                                                        address,
                                                        id,
                                                        port,
                                                    ))
                                        if (allow) accepted++
                                        process
                                            .getOutputStream()
                                            .write(
                                                ((if (allow) "A " else "P ") + sequence + "\n")
                                                    .toByteArray(StandardCharsets.US_ASCII)
                                            )
                                        process.getOutputStream().flush()
                                    }
                                }
                            }
                    } catch (error: Exception) {
                        failure = error.javaClass.getSimpleName() + ": " + error.message
                    } finally {
                        ready.countDown()
                    }
                },
                "HomePod-owned-getter-trial",
            )
        control.setDaemon(true)
        control.start()
        if (!ready.await(3, TimeUnit.SECONDS) || failure != null || !process.isAlive()) {
            close()
            throw IllegalStateException("Getter trial not ready: " + transcript)
        }
    }

    @Synchronized
    @Throws(Exception::class)
    override fun close() {
        if (closed) return
        closed = true
        stopping = true
        var clean: Boolean = false
        try {
            process.getOutputStream().close()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            control.join(1000)
            clean =
                process.exitValue() == 0 &&
                    transcript
                        .toString()
                        .matches("(?s).*DONE hits=[0-9]+ changed=[0-9]+ clean=1 ok=1.*".toRegex())
        } finally {
            if (process.isAlive()) process.destroyForcibly()
            Files.deleteIfExists(directory.resolve("probe"))
            Files.deleteIfExists(directory)
        }
        check(clean) { "Period preparation cleanup unconfirmed" }
        cleanupConfirmed = true
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun ownedConfig(snapshot: JSONObject, address: String?, id: Int, port: Int): Boolean {
            val ports = ArrayList<PeriodRouteGuard.Port?>()
            val configs = ArrayList<PeriodRouteGuard.Config?>()
            val rawPorts = snapshot.getJSONArray("ports")
            val rawConfigs = snapshot.getJSONArray("configs")
            for (i in 0 until rawPorts.length()) {
                val p = rawPorts.getJSONObject(i)
                ports.add(
                    PeriodRouteGuard.Port(
                        p.getInt("id"),
                        p.optInt("deviceType"),
                        p.optString("address"),
                    )
                )
            }
            for (i in 0 until rawConfigs.length()) {
                val c = rawConfigs.getJSONObject(i)
                configs.add(
                    PeriodRouteGuard.Config(
                        c.getInt("id"),
                        c.getInt("portId"),
                        c.getInt("rate"),
                        c.getBoolean("stereo"),
                        c.getBoolean("pcm16"),
                        c.optInt("handle"),
                    )
                )
            }
            return PeriodRouteGuard.allows(ports, configs, address, id, port)
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun ownedMixer(
            snapshot: JSONObject,
            address: String?,
            io: Int,
            frames: Int,
        ): Boolean {
            if (io <= 0) return false
            val configs = snapshot.getJSONArray("configs")
            val patches = snapshot.getJSONArray("patches")
            for (i in 0 until configs.length()) {
                val c = configs.getJSONObject(i)
                if (
                    c.optInt("handle") == io &&
                        c.getInt("portId") == 3 &&
                        HalPeriodExperiment.ownedConfig(snapshot, address, c.getInt("id"), 3)
                ) {
                    for (j in 0 until patches.length()) {
                        val p = patches.getJSONObject(j)
                        val sources = p.getJSONArray("sources")
                        if (
                            sources.length() == 1 &&
                                sources.getInt(0) == c.getInt("id") &&
                                p.getInt("minimumFrames") == frames
                        )
                            return true
                    }
                }
            }
            return false
        }

        @JvmStatic
        @Throws(Exception::class)
        fun requireSmallOutput(state: ReadOnlyHalState, address: String?): JSONObject? {
            return HalPeriodExperiment.requireOutput(state, address, PhoneTiming.fastest()!!)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun requireOutput(
            state: ReadOnlyHalState,
            address: String?,
            timing: PhoneTiming,
        ): JSONObject? {
            val io = OutputPeriodProbe.ownedOutput(state.snapshot()!!, address!!)
            val dump = ProcessBuilder("dumpsys", "media.audio_flinger").start()
            val bytes = dump.getInputStream().readNBytes(1048577)
            if (!dump.waitFor(2, TimeUnit.SECONDS) || bytes.size > 1048576) {
                dump.destroyForcibly()
                throw SecurityException("Mixer period unavailable")
            }
            for (section in
                String(bytes, StandardCharsets.UTF_8)
                    .replace("\r", "")
                    .split("Output thread ".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .toTypedArray()) {
                var matched: Boolean = false
                for (line in
                    section
                        .split("\n".toRegex())
                        .dropLastWhile { it.isEmpty() }
                        .toTypedArray()) if (line == "  I/O handle: " + io) matched = true
                if (!matched) continue
                var hal: Int = 0
                var normal: Int = 0
                var rate: Int = 0
                for (line in
                    section.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
                    if (line.trim { it <= ' ' }.startsWith("-")) break
                    val text = line.trim { it <= ' ' }
                    if (text.startsWith("HAL frame count: "))
                        hal = text.substring(17).trim { it <= ' ' }.toInt()
                    if (text.startsWith("Normal frame count: "))
                        normal = text.substring(20).trim { it <= ' ' }.toInt()
                    if (text.startsWith("Sample rate: "))
                        rate =
                            text
                                .substring(13)
                                .split(" ".toRegex())
                                .dropLastWhile { it.isEmpty() }
                                .toTypedArray()[0]
                                .toInt()
                }
                if (
                    hal == timing.periodFrames() && normal == timing.periodFrames() && rate == 44100
                )
                    return JSONObject()
                        .put("halFrames", hal)
                        .put("normalFrames", normal)
                        .put("sampleRate", rate)
            }
            throw SecurityException("Short period not confirmed")
        }

        @JvmStatic
        @JvmOverloads
        @Throws(Exception::class)
        fun run(
            mixer: Boolean = false,
            capture: Boolean = false,
            timing: PhoneTiming = PhoneTiming.fastest(),
        ): JSONObject? {
            HalPortProbe.rootPreflight()
            val state = ReadOnlyHalState()
            val baseline = state.snapshot().toString()
            if (Looper.myLooper() == null) Looper.prepare()
            val activity = Class.forName("android.app.ActivityThread")
            val thread = activity.getMethod("systemMain").invoke(null)
            val context: Context? =
                activity.getMethod("getSystemContext").invoke(thread) as Context?
            val owner = arrayOf<HalPeriodExperiment?>(null, null)
            val result = JSONObject().put("playbackCreated", capture).put("networkStarted", false)
            try {
                RegisteredPolicy(
                        context!!,
                        CaptureScope(false, 0, -1, true, false),
                        RegisteredPolicy.BeforeRoute { address: String? ->
                            owner[0] = HalPeriodExperiment(address, false, timing)
                            if (mixer) owner[1] = HalPeriodExperiment(address, true, timing)
                        },
                    )
                    .use { policy ->
                        result.put("active", state.snapshot())
                        result.put("requestedPeriodMs", timing.periodMs)
                        if (mixer)
                            result.put(
                                "confirmedOutput",
                                HalPeriodExperiment.requireOutput(state, policy.address, timing),
                            )
                        if (capture)
                            result.put(
                                "capture",
                                HalPortProbe.periodCapture(
                                    policy.address,
                                    UUID.randomUUID(),
                                    HalPortProbe.Ready {
                                        if (owner[1] != null) owner[1]!!.close()
                                        owner[0]!!.close()
                                    },
                                ),
                            )
                        else {
                            if (owner[1] != null) owner[1]!!.close()
                            owner[0]!!.close()
                        }
                        val dump = ProcessBuilder("dumpsys", "media.audio_flinger").start()
                        val text = dump.getInputStream().readNBytes(1048577)
                        if (dump.waitFor(2, TimeUnit.SECONDS) && text.size <= 1048576) {
                            val io =
                                OutputPeriodProbe.ownedOutput(state.snapshot()!!, policy.address)
                            var section: String = ""
                            for (part in
                                String(text, StandardCharsets.UTF_8)
                                    .replace("\r", "")
                                    .split("Output thread ".toRegex())
                                    .dropLastWhile { it.isEmpty() }
                                    .toTypedArray()) if (
                                part.contains("  I/O handle: " + io + "\n")
                            )
                                section = part
                            val fields = StringBuilder()
                            for (line in
                                section
                                    .split("\n".toRegex())
                                    .dropLastWhile { it.isEmpty() }
                                    .toTypedArray()) if (
                                !line.trim { it <= ' ' }.startsWith("-") &&
                                    (line.contains("frame count:") ||
                                        line.contains("Sample rate:") ||
                                        line.contains("I/O handle:") ||
                                        line.contains("FastMixer thread"))
                            )
                                fields.append(line.trim { it <= ' ' }).append('\n')
                            result.put("audioFlinger", fields.toString())
                        } else dump.destroyForcibly()
                    }
            } catch (error: Exception) {
                result.put("failure", error.javaClass.getSimpleName() + ": " + error.message)
            } finally {
                if (owner[1] != null) {
                    owner[1]!!.close()
                    result
                        .put("mixerTrace", owner[1]!!.transcript.toString())
                        .put("mixerApprovedCalls", owner[1]!!.accepted)
                }
                if (owner[0] != null) {
                    owner[0]!!.close()
                    result
                        .put("trace", owner[0]!!.transcript.toString())
                        .put("approvedCalls", owner[0]!!.accepted)
                        .put(
                            "traceFailure",
                            if (owner[0]!!.failure == null) JSONObject.NULL else owner[0]!!.failure,
                        )
                }
                var restored: Boolean = false
                for (i in 0..59) {
                    try {
                        restored = baseline == state.snapshot().toString()
                    } catch (unavailable: Exception) {
                        result.put("moduleUnavailable", true)
                        break
                    }
                    if (restored) break
                    Thread.sleep(50)
                }
                result.put("resourcesRestoredExactly", restored)
                if (!restored)
                    result.put("baseline", JSONObject(baseline)).put("after", state.snapshot())
            }
            return result
        }
    }
}
