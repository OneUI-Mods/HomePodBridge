package dev.ryuya.homepodbridge.halprobe

import android.content.Context
import android.os.Looper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmStatic
import org.json.JSONArray
import org.json.JSONObject

/* Root self-UID virtual output only, before any playback track exists; no PCM or network. */
object OutputPeriodProbe {
    @JvmStatic
    @Throws(Exception::class)
    fun run(): JSONObject? {
        HalPortProbe.rootPreflight()
        OutputPeriodProbe.pin(
            "/system/lib64/libaudiobase.so",
            "73ee495af55db5beb3852923c0b9cdf74d5a9667639b9cc87eda34e1b646786f",
        )
        OutputPeriodProbe.pin(
            "/system/lib64/libutils.so",
            "e1eb96ad194e2b2191c3a2a11d7f2d876fd57b30583d9fdaee1da001ba5b5e23",
        )
        val apk = System.getenv("CLASSPATH")
        if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex()))
            throw SecurityException("Installed helper required")
        System.load(apk + "!/lib/arm64-v8a/libhomepod_hal.so")
        val state = ReadOnlyHalState()
        val baseline = state.snapshot().toString()
        val result = JSONObject().put("playbackCreated", false).put("networkStarted", false)
        if (Looper.myLooper() == null) Looper.prepare()
        val activity = Class.forName("android.app.ActivityThread")
        val thread = activity.getMethod("systemMain").invoke(null)
        val context: Context? = activity.getMethod("getSystemContext").invoke(thread) as Context?
        try {
            RegisteredPolicy(context!!, CaptureScope(false, 0, -1, true, false)).use { policy ->
                val before = state.snapshot()
                val io = OutputPeriodProbe.ownedOutput(before!!, policy.address)
                if (io != OutputPeriodProbe.ownedOutput(state.snapshot()!!, policy.address))
                    throw SecurityException("Owned output changed")
                var values = OutputPeriodProbe.probe(io)
                if (values != null && values.size == 20 && values[18] == 0L) {
                    values = OutputPeriodProbe.executableProbe(io)
                    result.put("platformExecutable", true)
                }
                result
                    .put("requestedFrames", 88)
                    .put(
                        "platformResult",
                        if (values == null) JSONObject.NULL else JSONArray(values),
                    )
                result.put(
                    "ownedOutputUnchanged",
                    io == OutputPeriodProbe.ownedOutput(state.snapshot()!!, policy.address),
                )
            }
        } catch (failure: Exception) {
            result.put("failure", failure.javaClass.getSimpleName() + ": " + failure.message)
        } finally {
            var restored: Boolean = false
            for (i in 0..59) {
                restored = baseline == state.snapshot().toString()
                if (restored) break
                Thread.sleep(50)
            }
            result.put("resourcesRestoredExactly", restored)
        }
        return result
    }

    @JvmStatic
    @Throws(Exception::class)
    fun ownedOutput(state: JSONObject, address: String): Int {
        var port: Int = 0
        var config: Int = 0
        var io: Int = 0
        val ports = state.getJSONArray("ports")
        val configs = state.getJSONArray("configs")
        for (i in 0 until ports.length()) {
            val p = ports.getJSONObject(i)
            if (p.optInt("deviceType") == 143 && address == p.optString("address")) {
                if (port != 0) throw SecurityException("Ambiguous owned output")
                port = p.getInt("id")
            }
        }
        if (port <= 0) throw SecurityException("Owned output missing")
        for (i in 0 until configs.length()) {
            val c = configs.getJSONObject(i)
            if (c.getInt("portId") == port) {
                if (config != 0) throw SecurityException("Ambiguous device config")
                config = c.getInt("id")
            }
        }
        val patches = state.getJSONArray("patches")
        for (i in 0 until patches.length()) {
            val patch = patches.getJSONObject(i)
            val sinks = patch.getJSONArray("sinks")
            val sources = patch.getJSONArray("sources")
            if (sinks.length() != 1 || sinks.getInt(0) != config) continue
            if (sources.length() != 1 || io != 0) throw SecurityException("Ambiguous output patch")
            val source = sources.getInt(0)
            for (j in 0 until configs.length()) {
                val c = configs.getJSONObject(j)
                if (
                    c.getInt("id") == source &&
                        c.getInt("portId") == 3 &&
                        c.getInt("rate") == 44100 &&
                        c.getBoolean("stereo") &&
                        c.getBoolean("pcm16")
                )
                    io = c.getInt("handle")
            }
        }
        if (io <= 0 || io > 1000000) throw SecurityException("Owned output handle missing")
        return io
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun pin(file: String?, expected: String?) {
        val path = Path.of(file)
        if (
            Files.size(path) > 4194304 ||
                (HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                    ) != expected)
        )
            throw SecurityException("Unsupported platform ABI")
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun executableProbe(io: Int): LongArray {
        // Provisioned only by command-line development tooling; never accepts a caller path or
        // shell.
        val supplied = Path.of("/data/local/tmp/homepod-output-period-probe")
        if (Files.size(supplied) > 1048576) throw SecurityException("Unexpected probe size")
        val bytes = Files.readAllBytes(supplied)
        if (
            bytes.size > 1048576 ||
                (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) !=
                    "2a17b3593bf2c68221d4becd9f120f7c13730791945111a38d24805f462dc431")
        )
            throw SecurityException("Unverified platform executable")
        val directory =
            Files.createTempDirectory(
                Path.of("/data/local/tmp"),
                "homepod-period-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        val executable = directory.resolve("probe")
        var child: Process? = null
        try {
            Files.write(
                executable,
                bytes,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            Files.setPosixFilePermissions(
                executable,
                PosixFilePermissions.fromString("rwx------"),
            )
            child = ProcessBuilder(executable.toString(), io.toString()).start()
            check(!(!child.waitFor(6, TimeUnit.SECONDS) || child.exitValue() != 0)) {
                "Platform probe incomplete"
            }
            val text =
                String(
                        child.getInputStream().readNBytes(2049),
                        StandardCharsets.US_ASCII,
                    )
                    .trim { it <= ' ' }
            if (text.length > 2048) throw SecurityException("Invalid platform reply")
            val array = JSONArray(text)
            if (array.length() != 20) throw SecurityException("Invalid platform reply")
            val values = LongArray(20)
            for (i in values.indices) values[i] = array.getLong(i)
            return values
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly()
                child.waitFor(2, TimeUnit.SECONDS)
            }
            Files.deleteIfExists(executable)
            Files.deleteIfExists(directory)
        }
    }

    @JvmStatic private external fun probe(io: Int): LongArray?
}
