package dev.ryuya.homepodbridge.halprobe

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.LocalSocketAddress.Namespace
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.ArrayList
import java.util.List
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import org.json.JSONArray
import org.json.JSONObject

/* Native inspection runs in a child; the Java owner retains all original stream handles/FDs. */
object HalQueueMapping {
    private var forwardedOutput: OutputStream? = null
    private val preparedWorker: ThreadLocal<HalQueueMapping.PreparedWorker?> =
        ThreadLocal<HalQueueMapping.PreparedWorker?>()

    @JvmStatic
    private external fun inspect(
        fds: IntArray?,
        grants: LongArray?,
        quantum: Int,
        flags: Int,
    ): LongArray?

    @JvmStatic
    private external fun queryStandby(
        commandFds: IntArray?,
        commandGrants: LongArray?,
        replyFds: IntArray?,
        replyGrants: LongArray?,
    ): LongArray?

    @JvmStatic
    private external fun exitAfterStatus(
        commandFds: IntArray?,
        commandGrants: LongArray?,
        replyFds: IntArray?,
        replyGrants: LongArray?,
    ): Boolean

    @JvmStatic
    private external fun zeroTransfer(
        fds: Array<IntArray?>?,
        grants: Array<LongArray?>?,
        frames: Int,
        exitAfterInput: Boolean,
        outputOnly: Boolean,
    ): LongArray?

    @JvmStatic
    private external fun policyInputRead(
        fds: Array<IntArray?>?,
        grants: Array<LongArray?>?,
        forwardZero: Boolean,
        live: Boolean,
        sanitize: Boolean,
        frames: Int,
        eventWait: Boolean,
        directOutput: Int,
        ownerControl: Int,
    ): LongArray?

    @JvmStatic
    @JvmOverloads
    @Throws(Exception::class)
    fun inspectInChild(
        streams: MutableList<FrozenHalParcel.Stream?>,
        nonce: String,
        scenario: ProbeScenario,
        sink: OutputStream? = null,
        stopped: BooleanSupplier = BooleanSupplier { false },
        directSocket: FileDescriptor? = null,
    ): JSONObject? {
        if (directSocket != null && !scenario.live()) throw SecurityException()
        val apk = System.getenv("CLASSPATH")
        if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex()))
            throw SecurityException()
        val name: String = "hpb-map-" + nonce
        var child: java.lang.Process? = null
        var forwarder: Thread? = null
        var stopper: Thread? = null
        val prepared = HalQueueMapping.preparedWorker.get()
        try {
            (if (prepared == null) LocalServerSocket(name) else prepared.server).use { server ->
                val command =
                    ProcessBuilder(
                        "app_process",
                        "/system/bin",
                        HalQueueMapping::class.java.getName(),
                        name,
                        nonce,
                        android.os.Process.myPid().toString(),
                        scenario.workerMode,
                    )
                if (scenario == ProbeScenario.POLICY_FORWARD || scenario.live())
                    command.redirectError(Redirect.to(File("/dev/null")))
                if (directSocket != null) command.redirectOutput(Redirect.to(File("/dev/null")))
                child =
                    if (prepared == null) command.start()
                    else prepared.claim(nonce, scenario, directSocket!!)
                if (
                    directSocket == null &&
                        (scenario == ProbeScenario.POLICY_FORWARD || scenario.live())
                ) {
                    val producer: java.lang.Process? = child
                    // Keep stdout open until the resource owner's final cleanup and exit.
                    HalQueueMapping.forwardedOutput =
                        if (sink != null) sink else FileOutputStream(FileDescriptor.out)
                    forwarder =
                        Thread(
                            Runnable {
                                try {
                                    producer!!.getInputStream().use { input ->
                                        val block = ByteArray(4096)
                                        var count: Int
                                        while (
                                            (input.read(block).also { count = it }) != -1
                                        ) HalQueueMapping.forwardedOutput!!.write(block, 0, count)
                                        HalQueueMapping.forwardedOutput!!.flush()
                                    }
                                } catch (disconnected: IOException) {
                                    // Closing the reader makes the bounded native writer exit;
                                    // the Java owner
                                    // cleans up.
                                }
                            },
                            "HomePod-zero-forward",
                        )
                    forwarder.setDaemon(true)
                    forwarder.start()
                }
                val pending = StructPollfd()
                pending.fd = server.getFileDescriptor()
                pending.events = OsConstants.POLLIN.toShort()
                val connectDeadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                while (System.nanoTime() < connectDeadline && child!!.isAlive()) {
                    Os.poll(arrayOf<StructPollfd?>(pending), 100)
                    if ((pending.revents.toInt() and OsConstants.POLLIN) != 0) break
                }
                if ((pending.revents.toInt() and OsConstants.POLLIN) == 0) {
                    val reason: String? =
                        if (child!!.isAlive()) "connection timeout"
                        else
                            String(
                                child!!.getErrorStream().readNBytes(1024),
                                StandardCharsets.UTF_8,
                            )
                    throw IOException("Mapping worker did not connect: " + reason)
                }
                server.accept().use { socket ->
                    socket.setSoTimeout(
                        if (scenario.live()) 0
                        else if (scenario == ProbeScenario.POLICY_FORWARD) 15000 else 3000
                    )
                    val peer = socket.getPeerCredentials()
                    val peerStat =
                        String(
                            Files.readAllBytes(Path.of("/proc", peer.getPid().toString(), "stat")),
                            StandardCharsets.UTF_8,
                        )
                    val identity =
                        WorkerIdentity.authenticate(
                            peer.getPid(),
                            peer.getUid(),
                            android.os.Process.myPid(),
                            peerStat,
                            HalQueueMapping.procString(peer.getPid(), "status")!!,
                            HalQueueMapping.procString(peer.getPid(), "cmdline")!!,
                            nonce,
                            scenario,
                        )
                    if (scenario.live()) {
                        val worker: java.lang.Process? = child
                        stopper =
                            Thread(
                                Runnable {
                                    try {
                                        while (worker!!.isAlive() && !stopped.getAsBoolean()) Thread
                                            .sleep(100)
                                        if (
                                            worker!!.isAlive() &&
                                                !worker!!.waitFor(2, TimeUnit.SECONDS)
                                        )
                                            HalQueueMapping.killWorker(identity)
                                    } catch (ignored: Exception) {}
                                },
                                "HomePod-capture-client-death",
                            )
                        stopper.setDaemon(true)
                        stopper.start()
                    }
                    val descriptors = ArrayList<FileDescriptor?>()
                    val bytes = ByteArrayOutputStream()
                    val metadata = DataOutputStream(bytes)
                    metadata.writeUTF(nonce)
                    metadata.writeInt(streams.size * 3)
                    for (stream in streams) for (queue in
                        List.of<FrozenHalParcel.Queue?>(
                            stream!!.command,
                            stream!!.reply,
                            stream!!.data,
                        )) {
                        metadata.writeInt(queue.quantum)
                        metadata.writeInt(queue.flags)
                        metadata.writeInt(queue.fds!!.size)
                        metadata.writeInt(queue.grants!!.size)
                        for (fd in queue.fds!!) descriptors.add(fd!!.getFileDescriptor())
                        for (grant in queue.grants!!) {
                            metadata.writeInt(grant!!.fd)
                            metadata.writeLong(grant!!.offset.toLong())
                            metadata.writeLong(grant!!.extent)
                        }
                    }
                    metadata.writeBoolean(directSocket != null)
                    if (directSocket != null) descriptors.add(directSocket)
                    if (descriptors.size > 48 || bytes.size() > 8192) throw SecurityException()
                    socket.setFileDescriptorsForSend(descriptors.toTypedArray<FileDescriptor?>())
                    val output = DataOutputStream(socket.getOutputStream())
                    output.writeByte(0x51)
                    output.flush()
                    socket.setFileDescriptorsForSend(null)
                    output.writeInt(bytes.size())
                    output.write(bytes.toByteArray())
                    output.flush()
                    val result: String?
                    val readStarted = System.nanoTime()
                    try {
                        result = DataInputStream(socket.getInputStream()).readUTF()
                    } catch (unavailable: IOException) {
                        if (scenario == ProbeScenario.POLICY_FORWARD || scenario.live())
                            throw IOException("Zero forwarding worker unavailable", unavailable)
                        val readWaitNanos: Long = System.nanoTime() - readStarted
                        val outcome =
                            WorkerTermination.collect(
                                child!!,
                                readWaitNanos,
                                WorkerTermination.Killer {
                                    HalQueueMapping.killWorker(identity)
                                },
                            )
                        if (
                            scenario.acceptsFailure(
                                outcome!!.exitCode,
                                outcome!!.timedOut,
                                outcome!!.forced,
                                outcome!!.diagnostics,
                            )
                        )
                            return JSONObject()
                                .put("faultObserved", true)
                                .put("fault", scenario.workerMode)
                                .put("workerExit", outcome!!.exitCode)
                                .put("forcedTermination", outcome!!.forced)
                                .put("timedOut", outcome!!.timedOut)
                                .put("readWaitMs", readWaitNanos / 1000000)
                                .put("started", scenario.zeroFrames > 0)
                        throw IOException(
                            "Mapping child failed: " + outcome!!.diagnostics,
                            unavailable,
                        )
                    }
                    if (result.length > 2048) throw SecurityException()
                    if (!child!!.waitFor(2, TimeUnit.SECONDS) || child!!.exitValue() != 0)
                        throw IOException("Mapping worker failed")
                    return JSONObject(result)
                }
            }
        } finally {
            if (child != null) child.destroyForcibly()
            if (stopper != null) stopper.interrupt()
            if (forwarder != null) forwarder.join(500)
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun procString(pid: Int, name: String?): String? {
        return String(
            Files.readAllBytes(Path.of("/proc", pid.toString(), name)),
            StandardCharsets.UTF_8,
        )
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun killWorker(identity: WorkerIdentity) {
        try {
            if (
                !identity.matches(
                    HalQueueMapping.procString(identity.pid, "stat")!!,
                    HalQueueMapping.procString(identity.pid, "status")!!,
                    HalQueueMapping.procString(identity.pid, "cmdline")!!,
                )
            )
                throw SecurityException("Worker identity changed; signal refused")
        } catch (exited: NoSuchFileException) {
            return
        }
        android.os.Process.sendSignal(identity.pid, OsConstants.SIGKILL)
    }

    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        if (
            args.size != 4 ||
                android.os.Process.myUid() != 0 ||
                (args[0] != "hpb-map-" + args[1]) ||
                (UUID.fromString(args[1]).toString() != args[1]) ||
                !args[2].matches("[1-9][0-9]{0,6}".toRegex()) ||
                ProbeScenario.forWorker(args[3]) == null
        )
            throw SecurityException()
        val scenario = ProbeScenario.forWorker(args[3])
        if (scenario == ProbeScenario.POLICY_FORWARD || scenario!!.live()) System.setOut(System.err)
        val apk = System.getenv("CLASSPATH")
        if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex()))
            throw SecurityException()
        System.load(apk + "!/lib/arm64-v8a/libc++_shared.so")
        System.load(apk + "!/lib/arm64-v8a/libhomepod_fmq_probe.so")
        println("MAPPING_LIBRARY_READY")
        var incoming: Array<FileDescriptor?>? = null
        val owned = ArrayList<ParcelFileDescriptor?>()
        try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(args[0], Namespace.ABSTRACT))
                socket.setSoTimeout(if (scenario!!.live()) 10000 else 3000)
                val peer = socket.getPeerCredentials()
                if (peer.getUid() != 0 || peer.getPid() != args[2].toInt())
                    throw SecurityException()
                val input = DataInputStream(socket.getInputStream())
                if (input.readUnsignedByte() != 0x51) throw SecurityException()
                incoming = socket.getAncillaryFileDescriptors()
                println("MAPPING_FDS=" + (if (incoming == null) -1 else incoming.size))
                if (incoming == null || incoming.size == 0 || incoming.size > 48)
                    throw SecurityException()
                val length = input.readInt()
                if (length <= 0 || length > 8192) throw SecurityException()
                val data = ByteArray(length)
                input.readFully(data)
                val metadata = DataInputStream(ByteArrayInputStream(data))
                val queueCount: Int = if (scenario!!.policyInput()) 3 else 6
                if (metadata.readUTF() != args[1] || metadata.readInt() != queueCount)
                    throw SecurityException()
                if (scenario == ProbeScenario.EXIT_FDS) {
                    println("FAULT_CHECKPOINT=after-fds")
                    System.out.flush()
                    Runtime.getRuntime().halt(73)
                }
                val reports = JSONArray()
                val queueFds = ArrayList<IntArray?>()
                val queueGrants = ArrayList<LongArray?>()
                var next: Int = 0
                for (i in 0 until queueCount) {
                    val quantum = metadata.readInt()
                    val flags = metadata.readInt()
                    val fdCount = metadata.readInt()
                    val grantCount = metadata.readInt()
                    if (
                        quantum != intArrayOf(8, 56, 1)[i % 3] ||
                            flags != 1 ||
                            fdCount < 1 ||
                            fdCount > 8 ||
                            next + fdCount > incoming.size ||
                            (grantCount != 3 && grantCount != 4)
                    )
                        throw SecurityException()
                    val fds = IntArray(fdCount)
                    for (f in 0 until fdCount) {
                        val copy = ParcelFileDescriptor.dup(incoming[next++])
                        owned.add(copy)
                        fds[f] = copy.getFd()
                    }
                    val grants = LongArray(grantCount * 3)
                    for (g in 0 until grantCount) {
                        grants[g * 3] = metadata.readInt().toLong()
                        grants[g * 3 + 1] = metadata.readLong()
                        grants[g * 3 + 2] = metadata.readLong()
                    }
                    val result = HalQueueMapping.inspect(fds, grants, quantum, flags)
                    queueFds.add(fds)
                    queueGrants.add(grants)
                    println("MAPPING_NATIVE=" + result.contentToString())
                    if (result == null || result[0] < 0) {
                        for (fd in owned) {
                            val stat = Os.fstat(fd!!.getFileDescriptor())
                            println("MAPPING_FSTAT size=" + stat.st_size + " mode=" + stat.st_mode)
                        }
                        println("MAPPING_GRANTS=" + grants.contentToString())
                    }
                    println("MAPPING_QUEUE=" + i + " accepted=" + (result != null))
                    if (result == null || result.size != 3 || result[0] < 0)
                        throw SecurityException("Queue mapping rejected")
                    reports.put(
                        JSONObject()
                            .put("quantum", quantum)
                            .put("capacityBytes", result[0])
                            .put("readBytes", result[1])
                            .put("writtenBytes", result[2])
                    )
                }
                val direct = metadata.readBoolean()
                var directOutput: Int = -1
                var ownerControl: Int = -1
                if (direct) {
                    if (!scenario!!.live() || next + 1 != incoming.size) throw SecurityException()
                    val copy = ParcelFileDescriptor.dup(incoming[next++])
                    owned.add(copy)
                    directOutput = copy.getFd()
                    val control = ParcelFileDescriptor.dup(socket.getFileDescriptor())
                    owned.add(control)
                    ownerControl = control.getFd()
                }
                if (next != incoming.size || metadata.available() != 0) throw SecurityException()
                if (scenario!!.policyInput()) {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                    val result =
                        HalQueueMapping.policyInputRead(
                            queueFds.toTypedArray<IntArray?>(),
                            queueGrants.toTypedArray<LongArray?>(),
                            scenario == ProbeScenario.POLICY_FORWARD,
                            scenario!!.live(),
                            scenario == ProbeScenario.LIVE_ZERO,
                            if (scenario!!.live() || scenario == ProbeScenario.POLICY_HALF) 22
                            else 44,
                            scenario != ProbeScenario.POLICY_POLL,
                            directOutput,
                            ownerControl,
                        )
                    if (result == null || result.size != (if (scenario!!.live()) 13 else 16))
                        throw SecurityException("Input read unavailable")
                    val report =
                        JSONObject()
                            .put("mappedQueues", 3)
                            .put(
                                "framesPerBurst",
                                if (scenario!!.live() || scenario == ProbeScenario.POLICY_HALF) 22
                                else 44,
                            )
                            .put("passed", result[0] == 1L)
                            .put("capturedFrames", result[1])
                            .put("cycles", result[2])
                            .put("elapsedUs", result[3])
                            .put(
                                "effectiveFramesPerSecond",
                                if (result[3] > 0) result[1] * 1000000 / result[3] else 0,
                            )
                            .put("availabilityGated", true)
                            .put("maximumInputCommandUs", result[4])
                            .put("maximumPendingFrames", result[5])
                            .put("minimumPendingFrames", result[6])
                            .put("meanPendingFrames", result[8])
                            .put("sentFrames", if (scenario!!.live()) result[9] else result[1])
                            .put("idleFrames", if (scenario!!.live()) result[10] else 0)
                            .put("statusQueries", result[if (scenario!!.live()) 11 else 9])
                            .put("meanInputCommandUs", result[if (scenario!!.live()) 12 else 10])
                            .put("eventWait", scenario != ProbeScenario.POLICY_POLL)
                            .put("directSocket", direct)
                            .put("errorCode", result[7])
                    if (!scenario!!.live())
                        report
                            .put("producerEvents", result[11])
                            .put("producerStepMedianFrames", result[12])
                            .put("producerStepP95Frames", result[13])
                            .put("producerIntervalMedianUs", result[14])
                            .put("producerIntervalP95Us", result[15])
                    DataOutputStream(socket.getOutputStream()).writeUTF(report.toString())
                    return
                }
                if (scenario == ProbeScenario.EXIT_MAP) {
                    println("FAULT_CHECKPOINT=after-map")
                    System.out.flush()
                    Runtime.getRuntime().halt(73)
                }
                if (scenario == ProbeScenario.HANG) {
                    println("FAULT_CHECKPOINT=hang")
                    System.out.flush()
                    Thread.sleep(10000)
                    throw SecurityException("Hang fault was not terminated")
                }
                if (scenario == ProbeScenario.EXIT_STATUS) {
                    println("FAULT_CHECKPOINT=after-status")
                    System.out.flush()
                    HalQueueMapping.exitAfterStatus(
                        queueFds.get(0),
                        queueGrants.get(0),
                        queueFds.get(1),
                        queueGrants.get(1),
                    )
                    throw SecurityException("Status dispatch fault failed")
                }
                if (scenario!!.zeroFrames > 0) {
                    try {
                        android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_AUDIO
                        )
                    } catch (unavailable: RuntimeException) {}
                    if (scenario == ProbeScenario.ZERO_INPUT_EXIT) {
                        println("FAULT_CHECKPOINT=zero-input-exit")
                        System.out.flush()
                    }
                    val flow =
                        HalQueueMapping.zeroTransfer(
                            queueFds.toTypedArray<IntArray?>(),
                            queueGrants.toTypedArray<LongArray?>(),
                            scenario!!.zeroFrames,
                            scenario == ProbeScenario.ZERO_INPUT_EXIT,
                            scenario == ProbeScenario.ZERO_OUTPUT_BLOCK,
                        )
                    if (flow == null || flow.size != 13)
                        throw SecurityException("Zero transfer unavailable")
                    val transfer =
                        JSONObject()
                            .put("passed", flow[0] == 1L)
                            .put("framesPerBurst", flow[1])
                            .put("cycles", flow[2])
                            .put("sentFrames", flow[3])
                            .put("capturedFrames", flow[4])
                            .put("elapsedUs", flow[5])
                            .put("maximumOutputCommandUs", flow[6])
                            .put("maximumInputCommandUs", flow[7])
                            .put("maximumPendingFrames", flow[8])
                            .put("errorCode", flow[9])
                            .put("startFlags", flow[10])
                            .put("inputObservableFrames", flow[11])
                            .put("expectedInputFrames", flow[12])
                    transfer.put(
                        "expectedBackpressure",
                        scenario == ProbeScenario.ZERO_OUTPUT_BLOCK &&
                            flow[0] == 1L &&
                            flow[9] == 5L,
                    )
                    DataOutputStream(socket.getOutputStream())
                        .writeUTF(
                            JSONObject()
                                .put("mappedQueues", 6)
                                .put("started", flow[10] != 0L)
                                .put("zeroTransfer", transfer)
                                .toString()
                        )
                    return
                }
                val control = JSONArray()
                for (base in intArrayOf(0, 3)) {
                    val result =
                        HalQueueMapping.queryStandby(
                            queueFds.get(base),
                            queueGrants.get(base),
                            queueFds.get(base + 1),
                            queueGrants.get(base + 1),
                        )
                    if (result == null || result.size != 4)
                        throw SecurityException("Standby status command failed")
                    control.put(
                        JSONObject()
                            .put("input", base == 3)
                            .put("roundTripUs", result[0])
                            .put("state", result[1])
                            .put("audioBytes", result[2])
                            .put("xrunFrames", result[3])
                    )
                }
                DataOutputStream(socket.getOutputStream())
                    .writeUTF(
                        JSONObject()
                            .put("mappedQueues", 6)
                            .put("queues", reports)
                            .put("started", false)
                            .put("statusCommands", control)
                            .toString()
                    )
            }
        } finally {
            for (fd in owned) try {
                fd!!.close()
            } catch (ignored: Exception) {}
            if (incoming != null)
                for (fd in incoming) try {
                    Os.close(fd)
                } catch (ignored: Exception) {}
        }
    }

    /* Preloads only the isolated JVM/native library. No HAL FDs are sent before verification. */
    class PreparedWorker(nonce: String, scenario: ProbeScenario) : AutoCloseable {
        @JvmField val nonce: String
        @JvmField val scenario: ProbeScenario
        @JvmField val server: LocalServerSocket
        private var child: java.lang.Process? = null
        private var claimed: Boolean = false

        init {
            if (
                HalQueueMapping.preparedWorker.get() != null ||
                    !scenario.live() ||
                    (UUID.fromString(nonce).toString() != nonce)
            )
                throw SecurityException()
            this.nonce = nonce
            this.scenario = scenario
            val apk = System.getenv("CLASSPATH")
            if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk".toRegex()))
                throw SecurityException()
            server = LocalServerSocket("hpb-map-" + nonce)
            try {
                child =
                    ProcessBuilder(
                            "app_process",
                            "/system/bin",
                            HalQueueMapping::class.java.getName(),
                            "hpb-map-" + nonce,
                            nonce,
                            android.os.Process.myPid().toString(),
                            scenario.workerMode,
                        )
                        .redirectError(Redirect.to(File("/dev/null")))
                        .redirectOutput(Redirect.to(File("/dev/null")))
                        .start()
                HalQueueMapping.preparedWorker.set(this)
            } catch (failed: Exception) {
                server.close()
                throw failed
            }
        }

        fun claim(
            nonce: String?,
            scenario: ProbeScenario?,
            directSocket: FileDescriptor?,
        ): java.lang.Process? {
            if (
                claimed ||
                    (this.nonce != nonce) ||
                    this.scenario != scenario ||
                    directSocket == null
            )
                throw SecurityException("Prepared worker mismatch")
            claimed = true
            return child
        }

        override fun close() {
            if (HalQueueMapping.preparedWorker.get() == this)
                HalQueueMapping.preparedWorker.remove()
            if (child != null && child!!.isAlive()) child!!.destroyForcibly()
            try {
                server.close()
            } catch (ignored: IOException) {}
        }
    }
}
