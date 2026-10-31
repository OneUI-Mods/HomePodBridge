package dev.ryuya.homepodbridge

import android.os.Process
import dev.ryuya.homepodbridge.halprobe.PhonePreparation
import dev.ryuya.homepodbridge.halprobe.PhoneTiming
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ArrayList
import java.util.HashSet
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import java.util.regex.Pattern
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Exact-build read-only inspection; optional bounded quiescence check never writes memory. */
object HalRootProbe {
    private const val MODULE: String = "/vendor/lib64/libaudioserviceexampleimpl.so"
    private const val PIPE: String = "/vendor/lib64/libnbaio_mono.so"
    private const val MODULE_SHA: String =
        "ef1bad7d1ae1d66a6ac6b39a009fba43e00031bb9a3316e435965d4e91564d61"
    private const val PIPE_SHA: String =
        "c363ecab034babaa344163bc4aaf67dfe42945055c433e0640ba555b86d159aa"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (
                Process.myUid() != 0 ||
                    args.size < 1 ||
                    args.size > 2 ||
                    (args.size == 2 &&
                        !java.util.List.of<String?>(
                                "freeze",
                                "tune",
                                "restore",
                                "brief",
                                "restore-brief",
                                "minimum",
                                "restore-minimum",
                                "direct",
                                "restore-direct",
                                "direct-752",
                                "restore-direct-752",
                                "direct-768",
                                "restore-direct-768",
                                "tiny-period",
                                "restore-tiny-period",
                                "latency-window",
                                "restore-latency-window",
                            )
                            .contains(args[1]) &&
                        HalRootProbe.manualOperation(args[1]) < 0 &&
                        PhonePreparation.parse(args[1]) == null) ||
                    !(args[0] == "none" || args[0].matches("[0-9]+:ap:[0-9]+mixp:[0-9]+".toRegex()))
            )
                throw IOException("Invalid probe context")
            HalRootProbe.verify(HalRootProbe.MODULE, HalRootProbe.MODULE_SHA)
            HalRootProbe.verify(HalRootProbe.PIPE, HalRootProbe.PIPE_SHA)
            val query =
                ProcessBuilder("dumpsys", "--pid", "android.hardware.audio.core.IModule/r_submix")
                    .start()
            val pid =
                String(query.getInputStream().readNBytes(128), StandardCharsets.US_ASCII).trim {
                    it <= ' '
                }
            if (!query.waitFor(2, TimeUnit.SECONDS) || !pid.matches("[0-9]{1,7}".toRegex()))
                throw IOException("Missing submix service")
            val proc = Path.of("/proc", pid)
            val status = String(Files.readAllBytes(proc.resolve("status")), StandardCharsets.UTF_8)
            if (!status.matches("(?s).*\\nUid:\\s+1041\\s+1041\\s+1041\\s+1041\\s*\\n.*".toRegex()))
                throw IOException("Unexpected service identity")
            if (status.contains("State:\tT") || status.contains("State:\tt"))
                throw IOException("Service already stopped")
            val command =
                String(Files.readAllBytes(proc.resolve("cmdline")), StandardCharsets.UTF_8)
            if (
                !command
                    .split("\u0000".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .toTypedArray()[0]
                    .endsWith("audiohalservice.qti")
            )
                throw IOException("Unexpected service executable")
            HalRootProbe.Memory(proc).use { memory ->
                val base = memory.base(HalRootProbe.MODULE)
                val pipeBase = memory.base(HalRootProbe.PIPE)
                val before = memory.read(base + 0x1b4730, 24)
                val count = HalRootProbe.number(before, 16)
                if (count < 0 || count > 16) throw IOException("Unrecognized route count")
                if (args[0] == "none") {
                    println(
                        ("Read-only exact-build service verified; routes=" +
                            count +
                            " mappedRegions=" +
                            memory.maps.size +
                            "; no owned registration; no route objects inspected")
                    )
                    return
                }
                val pending = ArrayList<Long?>()
                val seen = HashSet<Long?>()
                pending.add(HalRootProbe.number(before, 8))
                var report: String? = null
                var observedTarget: Long = -1
                while (!pending.isEmpty()) {
                    val node = HalRootProbe.pointer(pending.removeAt(pending.size - 1)!!)
                    if (node == 0L) continue
                    if (seen.size >= count || !seen.add(node))
                        throw IOException("Unstable route tree")
                    val entry = memory.read(node, 80)
                    pending.add(HalRootProbe.number(entry, 0))
                    pending.add(HalRootProbe.number(entry, 8))
                    if (ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).getInt(56) != 0)
                        continue
                    val address = memory.string(node + 32)
                    if (address != args[0]) continue
                    if (report != null) throw IOException("Ambiguous registration")
                    val route = HalRootProbe.pointer(HalRootProbe.number(entry, 64))
                    val routeData = memory.read(route, 144)
                    val config = ByteBuffer.wrap(routeData).order(ByteOrder.LITTLE_ENDIAN)
                    if (
                        config.getInt(0x6c) != 1 ||
                            config.getInt(0x28) != 44100 ||
                            config.getLong(0x58) != 4L
                    )
                        throw IOException("Unexpected owned route format or reference count")
                    val sink = HalRootProbe.pointer(config.getLong(0x80))
                    val pipeData = memory.read(sink, 256)
                    val capacity = HalRootProbe.number(pipeData, 0x40)
                    val target = HalRootProbe.number(pipeData, 0xe8)
                    observedTarget = target
                    if (
                        HalRootProbe.pointer(HalRootProbe.number(pipeData, 0)) !=
                            pipeBase + 0x8018 ||
                            HalRootProbe.number(pipeData, 0x30) != 4L ||
                            capacity != 4096L ||
                            target <= 0 ||
                            target > capacity
                    )
                        throw IOException("Unexpected owned pipe identity")
                    if (
                        !entry.contentEquals(memory.read(node, 80)) ||
                            HalRootProbe.pointer(
                                HalRootProbe.number(memory.read(route + 0x80, 8), 0)
                            ) != sink
                    )
                        throw IOException("Owned route changed during observation")
                    report =
                        ("Read-only owned route verified; references=1 rate=44100 frameBytes=4 capacityFrames=" +
                            capacity +
                            " targetFillFrames=" +
                            target +
                            " targetFillMs=" +
                            target * 1000.0 / 44100 +
                            "; no memory modified; concurrent snapshot, not a lifetime/quiescence" +
                            " guarantee")
                }
                if (
                    seen.size.toLong() != count ||
                        !before.contentEquals(memory.read(base + 0x1b4730, 24))
                )
                    throw IOException("Route set changed during observation")
                println(if (report == null) "No matching live owned route" else report)
                if (report != null && args.size == 2) {
                    if (args[1].startsWith("restore") && observedTarget == 2587L) {
                        println("Original reserve already present")
                        return
                    }
                    val apk = System.getenv("CLASSPATH")
                    if (
                        apk == null ||
                            !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex())
                    )
                        throw IOException("Invalid native helper path")
                    System.load(apk + "!/lib/arm64-v8a/libhomepod_hal.so")
                    val preparation = PhonePreparation.parse(args[1])
                    if (preparation != null) {
                        val reserve =
                            HalRootProbe.freezeCheck(
                                pid.toInt(),
                                base,
                                pipeBase,
                                args[0],
                                preparation.reserveOperation(),
                            )
                        println(reserve)
                        if (
                            !reserve!!.contains(
                                "Owned reserve changed: 2587 -> " +
                                    preparation.timing!!.reserveFrames +
                                    ";"
                            )
                        )
                            return
                        val window =
                            HalRootProbe.freezeCheck(
                                pid.toInt(),
                                base,
                                pipeBase,
                                args[0],
                                preparation.windowOperation(),
                            )
                        println(window)
                        if (
                            !window!!.contains(
                                ("Owned latency window changed: 4096 -> " +
                                    preparation.timing!!.latencyWindowFrames() +
                                    ";")
                            )
                        )
                            println(
                                HalRootProbe.freezeCheck(
                                    pid.toInt(),
                                    base,
                                    pipeBase,
                                    args[0],
                                    preparation.rollbackOperation(),
                                )
                            )
                        return
                    }
                    var operation =
                        java.util.List.of<String?>(
                                "freeze",
                                "tune",
                                "restore",
                                "brief",
                                "restore-brief",
                                "minimum",
                                "restore-minimum",
                                "direct",
                                "restore-direct",
                                "direct-752",
                                "restore-direct-752",
                                "direct-768",
                                "restore-direct-768",
                                "tiny-period",
                                "restore-tiny-period",
                                "latency-window",
                                "restore-latency-window",
                            )
                            .indexOf(args[1])
                    if (operation < 0) operation = HalRootProbe.manualOperation(args[1])
                    println(
                        HalRootProbe.freezeCheck(pid.toInt(), base, pipeBase, args[0], operation)
                    )
                }
            }
        } catch (error: Exception) {
            println(
                ("Read-only route probe unavailable: " +
                    error.javaClass.getSimpleName() +
                    ": " +
                    error.message)
            )
        }
    }

    @JvmStatic
    private fun manualOperation(value: String?): Int {
        val m =
            Pattern.compile("(reserve|restore-reserve|window|restore-window)-([0-9]{2,4})")
                .matcher(value)
        if (!m.matches()) return -1
        val number = m.group(2).toInt()
        val kind = m.group(1)
        if (kind.endsWith("reserve")) {
            if (number < 64 || number > 800) return -1
            return (if (kind.startsWith("restore")) 20000 else 10000) + number
        }
        var supported: Boolean = false
        for (ms in 1..21) if (PhoneTiming(ms, 96).latencyWindowFrames() == number) supported = true
        return if (supported) (if (kind.startsWith("restore")) 40000 else 30000) + number else -1
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun verify(file: String?, expected: String?) {
        if (Files.size(Path.of(file)) > 4 * 1024 * 1024)
            throw IOException("Unexpected library size")
        val bytes = Files.readAllBytes(Path.of(file))
        if (bytes.size > 4 * 1024 * 1024) throw IOException("Unexpected library size")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = StringBuilder()
        for (value in digest) hex.append(String.format("%02x", value.toInt() and 255))
        if (hex.toString() != expected) throw IOException("Unsupported HAL build")
    }

    @JvmStatic
    private fun number(bytes: ByteArray?, offset: Int): Long {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(offset)
    }

    @JvmStatic
    private fun pointer(value: Long): Long {
        return value and 0x00ffffffffffffffL
    }

    @JvmStatic
    private external fun freezeCheck(
        pid: Int,
        module: Long,
        mono: Long,
        registration: String?,
        operation: Int,
    ): String?

    @JvmRecord
    private data class Mapping(
        val begin: Long,
        val end: Long,
        val readable: Boolean,
        val offset: Long,
        val path: String?,
    ) {
        override fun toString(): String =
            "Mapping[begin=$begin, end=$end, readable=$readable, offset=$offset, path=$path]"
    }

    private class Memory(proc: Path) : AutoCloseable {
        private val file: RandomAccessFile
        val maps: MutableList<HalRootProbe.Mapping?> = ArrayList<HalRootProbe.Mapping?>()

        init {
            for (line in Files.readAllLines(proc.resolve("maps"))) {
                val fields = line.split("\\s+".toRegex(), limit = 6).toTypedArray()
                val range =
                    fields[0].split("-".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                maps.add(
                    HalRootProbe.Mapping(
                        range[0].toLong(16),
                        range[1].toLong(16),
                        fields[1].startsWith("r"),
                        fields[2].toLong(16),
                        if (fields.size == 6) fields[5] else "",
                    )
                )
            }
            file = RandomAccessFile(proc.resolve("mem").toFile(), "r")
        }

        @Throws(IOException::class)
        fun base(path: String?): Long {
            return maps
                .stream()
                .filter { m: HalRootProbe.Mapping? ->
                    m!!.path.equals(path) && m!!.offset == 0L && m!!.readable
                }
                .findFirst()
                .orElseThrow<IOException?>(Supplier { IOException("Module not mapped") })!!
                .begin
        }

        @Throws(IOException::class)
        fun read(address: Long, length: Int): ByteArray {
            val at = HalRootProbe.pointer(address)
            if (
                at < 65536 ||
                    length < 0 ||
                    length > 4096 ||
                    maps.stream().noneMatch { m: HalRootProbe.Mapping? ->
                        m!!.readable && at >= m!!.begin && at + length <= m!!.end
                    }
            )
                throw IOException("Invalid bounded memory range")
            val result = ByteArray(length)
            file.seek(at)
            file.readFully(result)
            return result
        }

        @Throws(IOException::class)
        fun string(address: Long): String? {
            val value = read(address, 24)
            val large: Boolean = (value[0].toInt() and 1) != 0
            val length: Long =
                if (large) HalRootProbe.number(value, 8)
                else ((value[0].toInt() and 255) shr 1).toLong()
            if (length < 0 || length > 128) throw IOException("Invalid registration length")
            val text =
                read(if (large) HalRootProbe.number(value, 16) else address + 1, length.toInt() + 1)
            if (text[length.toInt()].toInt() != 0)
                throw IOException("Invalid registration terminator")
            return String(text, 0, length.toInt(), StandardCharsets.UTF_8)
        }

        @Throws(IOException::class)
        override fun close() {
            file.close()
        }
    }
}
