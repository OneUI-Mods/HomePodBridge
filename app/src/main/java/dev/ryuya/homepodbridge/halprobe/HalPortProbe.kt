package dev.ryuya.homepodbridge.halprobe

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator
import android.os.Process
import dev.ryuya.homepodbridge.TextCompatibility
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.ArrayList
import java.util.HexFormat
import java.util.List
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized
import org.json.JSONArray
import org.json.JSONObject

/* Bounded virtual-route probes; data operations use only self-owned streams and fixed zeros. */
class HalPortProbe
private constructor(nonce: UUID, rate: Int, fast: Boolean, registration: String?) :
    OwnedHalResources.Backend {
    private val state: ReadOnlyHalState = ReadOnlyHalState()
    private val epoch: String? = HalPortProbe.epoch()
    private val address: String
    private val tag: OwnedHalResources.Tag
    private val rate: Int
    private val fast: Boolean
    private var ledger: StreamOpenLedger? = null
    private val streams: MutableList<HalPortProbe.OpenStream?> =
        CopyOnWriteArrayList<HalPortProbe.OpenStream?>()
    private val stopRequested: AtomicBoolean = AtomicBoolean()

    private var externalStopped: BooleanSupplier = BooleanSupplier { false }

    private fun isStopping(): Boolean {
        return stopRequested.get() || externalStopped.getAsBoolean()
    }

    private class OpenStream(binder: IBinder, input: Boolean, config: Int) {
        @JvmField val binder: IBinder
        @JvmField val input: Boolean
        @JvmField val config: Int
        @JvmField @Volatile var common: IBinder? = null
        @JvmField @Volatile var descriptor: FrozenHalParcel.Stream? = null
        @JvmField @Volatile var closed: Boolean = false

        init {
            this.binder = binder
            this.input = input
            this.config = config
        }
    }

    constructor(nonce: UUID, rate: Int, fast: Boolean) : this(nonce, rate, fast, null)

    init {
        require(!(rate != 44100 && rate != 32000 && rate != 16000 && rate != 8000))
        this.rate = rate
        this.fast = fast
        if (registration != null && !registration.matches("[0-9]+:ap:[0-9]+mixp:[0-9]+".toRegex()))
            throw SecurityException("Unexpected policy registration")
        address = if (registration == null) "HomePodBridgeDirect:" + nonce else registration
        val marker: Int = (nonce.hashCode() and 0x07ffffff) or 0x40000000
        tag =
            OwnedHalResources.Tag(
                "HomePodBridgeDirect:" + nonce,
                3,
                4,
                marker,
                marker xor 0x20000000,
            )
    }

    /* Borrow a policy-owned input port, owning only the newly returned config and patch IDs. */
    fun interface Ready {
        @Throws(Exception::class) fun run()
    }

    @Throws(Exception::class)
    override fun snapshot(): OwnedHalResources.Snapshot? {
        if (!state.module.isBinderAlive()) {
            val current = HalPortProbe.epoch()
            if (epoch == current)
                throw IOException("Binder unavailable but original module still exists")
            return OwnedHalResources.Snapshot(
                current,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
            )
        }
        return HalPortProbe.decode(state.snapshot()!!, epoch)
    }

    override fun streamsClosed(): Boolean {
        return ledger != null &&
            ledger!!.allClosed() &&
            streams.stream().allMatch { s: HalPortProbe.OpenStream? -> s!!.closed }
    }

    @Synchronized
    fun closeStreams() {
        for (i in streams.indices.reversed()) {
            val s = streams.get(i)
            if (s!!.closed) continue
            try {
                HalPortProbe.common(s!!)
                HalPortProbe.call(s!!.common!!, "android.hardware.audio.core.IStreamCommon", 2)!!
                    .recycle()
                HalPortProbe.call(s!!.common!!, "android.hardware.audio.core.IStreamCommon", 1)!!
                    .recycle()
                ledger!!.closed(s!!.config)
                s!!.closed = true
            } catch (ignored: Exception) {
                // Binder failure does not prove that the module or stream has gone away.
            } finally {
                if (s!!.descriptor != null) s!!.descriptor!!.close()
            }
        }
    }

    @Throws(Exception::class)
    fun open(config: Int, input: Boolean, frames: Int): JSONObject? {
        check(!isStopping()) { "Probe stopping" }
        require(!(frames <= 0 || frames > 262144))
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val holder = arrayOf<HalPortProbe.OpenStream?>(null)
        try {
            data.writeInterfaceToken(ReadOnlyHalState.DESCRIPTOR)
            FrozenHalParcel.writeOpenArgs(data, config, frames.toLong(), input)
            ledger!!.begin(config, input)
            check(state.module.transact(if (input) 14 else 15, data, reply, 0)) {
                "Open unavailable"
            }
            reply.readException()
            val desc =
                FrozenHalParcel.stream(
                    reply,
                    Consumer { binder: IBinder? ->
                        val stream = HalPortProbe.OpenStream(binder!!, input, config)
                        streams.add(stream)
                        holder[0] = stream
                        try {
                            ledger!!.received(config)
                        } catch (error: Exception) {
                            throw IllegalStateException("Stream journal unavailable", error)
                        }
                        check(!isStopping()) { "Probe stopping" }
                        try {
                            HalPortProbe.common(stream)
                        } catch (failure: Exception) {
                            throw IllegalStateException(failure)
                        }
                    },
                )
            holder[0]!!.descriptor = desc
            return JSONObject()
                .put("input", input)
                .put("frameBytes", desc.frameBytes)
                .put("bufferFrames", desc.bufferFrames)
                .put("commandQuantum", desc.command!!.quantum)
                .put("replyQuantum", desc.reply!!.quantum)
                .put("dataBytes", desc!!.data!!.grants!![2]!!.extent)
                .put("openedUnstarted", true)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Throws(Exception::class)
    private fun remove(transaction: Int, id: Int) {
        if (transaction != 7 && transaction != 19 && transaction != 20) throw SecurityException()
        val input = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            input.writeInterfaceToken(ReadOnlyHalState.DESCRIPTOR)
            input.writeInt(id)
            check(state.module.transact(transaction, input, reply, 0)) { "Cleanup unavailable" }
            reply.readException()
        } finally {
            input.recycle()
            reply.recycle()
        }
    }

    @Throws(Exception::class)
    override fun removePatch(id: Int) {
        remove(19, id)
    }

    @Throws(Exception::class)
    override fun removeConfig(id: Int) {
        remove(20, id)
    }

    @Throws(Exception::class)
    override fun disconnectPort(id: Int) {
        remove(7, id)
    }

    @Throws(Exception::class)
    fun configure(portId: Int, input: Boolean, mix: Boolean): Int {
        check(!isStopping()) { "Probe stopping" }
        var port: Any? = null
        for (candidate in state.objects(11, "android.media.audio.common.AudioPort")!!) if (
            ReadOnlyHalState.integer(candidate!!, "id") == portId
        )
            port = candidate
        checkNotNull(port) { "Owned port unavailable" }
        val requested =
            Class.forName("android.media.audio.common.AudioPortConfig")
                .getConstructor()
                .newInstance()
        HalPortProbe.number(requested, "portId", portId)
        val sampleField = requested.javaClass.getField("sampleRate")
        val sample = sampleField.getType().getConstructor().newInstance()
        HalPortProbe.number(sample, "value", rate)
        sampleField.set(requested, sample)
        val maskField = requested.javaClass.getField("channelMask")
        maskField.set(
            requested,
            maskField
                .getType()
                .getMethod("layoutMask", Int::class.javaPrimitiveType)
                .invoke(null, 3),
        )
        val formatField = requested.javaClass.getField("format")
        val format = formatField.getType().getConstructor().newInstance()
        HalPortProbe.number(format, "type", 1)
        HalPortProbe.number(format, "pcm", 1)
        format.javaClass.getField("encoding").set(format, "")
        formatField.set(requested, format)
        requested.javaClass.getField("flags").set(requested, ReadOnlyHalState.field(port, "flags"))
        var requestedFlags: Int = 0
        if (fast && mix) {
            val bit =
                Class.forName(
                        "android.media.audio.common." +
                            (if (input) "AudioInputFlags" else "AudioOutputFlags")
                    )
                    .getField("FAST")
                    .getInt(null)
            requestedFlags = 1 shl bit
            val field = requested.javaClass.getField("flags")
            field.set(
                requested,
                field
                    .getType()
                    .getMethod(if (input) "input" else "output", Int::class.javaPrimitiveType)
                    .invoke(null, requestedFlags),
            )
        }
        var ext = ReadOnlyHalState.field(port, "ext")
        if (mix) {
            if ((input && portId != tag.inputMix) || (!input && portId != tag.outputMix))
                throw SecurityException()
            val detail =
                ReadOnlyHalState.call(ext!!, "getMix")!!.javaClass.getConstructor().newInstance()
            HalPortProbe.number(
                detail,
                "handle",
                if (input) tag.inputHandle else tag.outputHandle,
            )
            val use = detail.javaClass.getField("usecase")
            use.set(
                detail,
                use.getType()
                    .getMethod(if (input) "source" else "stream", Int::class.javaPrimitiveType)
                    .invoke(null, if (input) 8 else 3),
            )
            ext = ext!!.javaClass.getMethod("mix", detail.javaClass).invoke(null, detail)
        } else if (
            snapshot()!!
                .ports!!
                .stream()
                .noneMatch({ p ->
                    p!!.id === portId &&
                        address == p!!.address &&
                        p!!.type === (if (input) 11 else 143)
                })
        )
            throw SecurityException("Not an owned virtual device")
        requested.javaClass.getField("ext").set(requested, ext)
        val before = snapshot()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ReadOnlyHalState.DESCRIPTOR)
            data.writeTypedObject<Parcelable?>(requested as Parcelable?, 0)
            check(state.module.transact(18, data, reply, 0)) { "Configure unavailable" }
            reply.readException()
            val accepted = reply.readBoolean()
            val suggested =
                reply.readTypedObject<Any?>(
                    (requested.javaClass.getField("CREATOR").get(null) as Creator<Any?>?)!!
                )
            val id: Int = if (suggested == null) 0 else ReadOnlyHalState.integer(suggested, "id")
            check(
                !(!accepted ||
                    id <= 0 ||
                    before!!.configs!!.stream().anyMatch({ c -> c!!.id === id }) ||
                    ReadOnlyHalState.integer(suggested!!, "portId") != portId)
            ) {
                "Port format rejected: " + suggested
            }
            val actual = ReadOnlyHalState.format(suggested!!)
            if (
                actual.getInt("rate") != rate ||
                    !actual.getBoolean("pcm16") ||
                    !actual.getBoolean("stereo")
            )
                throw SecurityException("Unexpected configured format")
            if (
                mix &&
                    ((ReadOnlyHalState.call(
                            ReadOnlyHalState.field(suggested!!, "flags")!!,
                            if (input) "getInput" else "getOutput",
                        ) as Number)
                        .toInt() != requestedFlags)
            )
                throw SecurityException("Requested flags not honored")
            if (
                mix &&
                    (ReadOnlyHalState.integer(
                        ReadOnlyHalState.call(
                            ReadOnlyHalState.field(suggested!!, "ext")!!,
                            "getMix",
                        )!!,
                        "handle",
                    ) != (if (input) tag.inputHandle else tag.outputHandle))
            )
                throw SecurityException("Mix handle changed")
            return id
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Throws(Exception::class)
    fun patch(source: Int, sink: Int): FrozenHalParcel.Patch? {
        check(!isStopping()) { "Probe stopping" }
        val before = snapshot()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ReadOnlyHalState.DESCRIPTOR)
            FrozenHalParcel.writePatch(data, 0, intArrayOf(source), intArrayOf(sink))
            check(state.module.transact(17, data, reply, 0)) { "Patch unavailable" }
            reply.readException()
            val value = FrozenHalParcel.patch(reply)
            if (
                value.id <= 0 ||
                    before!!.patches!!.stream().anyMatch({ p -> p!!.id === value.id }) ||
                    !value.sources.contentEquals(intArrayOf(source)) ||
                    !value.sinks.contentEquals(intArrayOf(sink))
            )
                throw SecurityException("Unexpected patch identity")
            return value
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Throws(Exception::class)
    fun createVirtualPort(type: Int): Int {
        check(!isStopping()) { "Probe stopping" }
        var requested: Any? = null
        for (port in state.objects(11, "android.media.audio.common.AudioPort")!!) {
            val ext = ReadOnlyHalState.field(port!!, "ext")
            if (!ReadOnlyHalState.tag(ext!!, "device")) continue
            val device =
                ReadOnlyHalState.field(ReadOnlyHalState.call(ext!!, "getDevice")!!, "device")
            val description = ReadOnlyHalState.field(device!!, "type")
            val address = ReadOnlyHalState.field(device!!, "address")
            if (
                ReadOnlyHalState.integer(description!!, "type") == type &&
                    "virtual" == ReadOnlyHalState.field(description!!, "connection") &&
                    ReadOnlyHalState.tag(address!!, "id") &&
                    "" == ReadOnlyHalState.call(address!!, "getId")
            ) {
                check(requested == null) { "Ambiguous template" }
                requested = port
            }
        }
        checkNotNull(requested) { "Virtual template unavailable" }
        val device =
            ReadOnlyHalState.field(
                ReadOnlyHalState.call(ReadOnlyHalState.field(requested, "ext")!!, "getDevice")!!,
                "device",
            )
        val value =
            Class.forName("android.media.audio.common.AudioDeviceAddress")
                .getMethod("id", String::class.java)
                .invoke(null, address)
        device!!.javaClass.getField("address").set(device, value)
        val input = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            input.writeInterfaceToken(ReadOnlyHalState.DESCRIPTOR)
            input.writeTypedObject<Parcelable?>(requested as Parcelable?, 0)
            check(state.module.transact(6, input, reply, 0)) { "Connect unavailable" }
            reply.readException()
            val returned =
                reply.readTypedObject<Any?>(
                    (Class.forName("android.media.audio.common.AudioPort")
                        .getField("CREATOR")
                        .get(null) as Creator<Any?>?)!!
                )
            val id = ReadOnlyHalState.integer(returned!!, "id")
            val current = snapshot()
            if (
                current!!
                    .ports!!
                    .stream()
                    .noneMatch({ p ->
                        p!!.id === id && p!!.type === type && address == p!!.address
                    })
            )
                throw SecurityException("Created port identity changed")
            return id
        } finally {
            input.recycle()
            reply.recycle()
        }
    }

    companion object {
        private val diagnosticScenario: ThreadLocal<ProbeScenario?> = ThreadLocal<ProbeScenario?>()

        @JvmStatic
        @Throws(Exception::class)
        fun policyCapture(
            address: String,
            nonce: UUID,
            target: Int,
            forwardZero: Boolean,
        ): JSONObject {
            return inputSession(
                address,
                nonce,
                target,
                forwardZero,
                false,
                true,
                false,
                null,
                BooleanSupplier { false },
                HalPortProbe.Ready {},
            )
        }

        @JvmStatic
        @JvmOverloads
        @Throws(Exception::class)
        fun inputSession(
            address: String,
            nonce: UUID,
            target: Int,
            forwardZero: Boolean,
            live: Boolean,
            zeroOnly: Boolean,
            pulse: Boolean,
            sink: OutputStream?,
            stopped: BooleanSupplier,
            ready: HalPortProbe.Ready,
            directSocket: FileDescriptor? = null,
        ): JSONObject {
            return HalPortProbe.inputSession(
                address,
                nonce,
                target,
                forwardZero,
                live,
                zeroOnly,
                pulse,
                sink,
                stopped,
                ready,
                directSocket,
                HalPortProbe.Ready {},
                928,
                null,
            )
        }

        @JvmStatic
        @Throws(Exception::class)
        fun periodCapture(address: String, nonce: UUID, afterOpen: HalPortProbe.Ready): JSONObject {
            HalPortProbe.diagnosticScenario.set(ProbeScenario.POLICY_HALF)
            try {
                return HalPortProbe.inputSession(
                    address,
                    nonce,
                    96,
                    false,
                    false,
                    true,
                    false,
                    null,
                    BooleanSupplier { false },
                    HalPortProbe.Ready {},
                    null,
                    afterOpen,
                    96,
                    PhoneTiming.fastest(),
                )
            } finally {
                HalPortProbe.diagnosticScenario.remove()
            }
        }

        @JvmStatic
        @JvmOverloads
        @Throws(Exception::class)
        fun livePeriodCapture(
            address: String,
            nonce: UUID,
            zero: Boolean,
            pulse: Boolean,
            output: OutputStream?,
            stopped: BooleanSupplier,
            ready: HalPortProbe.Ready,
            directSocket: FileDescriptor?,
            afterOpen: HalPortProbe.Ready,
            timing: PhoneTiming = PhoneTiming.fastest(),
        ): JSONObject {
            HalPeriodExperiment.requireOutput(ReadOnlyHalState(), address, timing)
            return HalPortProbe.inputSession(
                address,
                nonce,
                timing.reserveFrames,
                false,
                true,
                zero,
                pulse,
                output,
                stopped,
                ready,
                directSocket,
                afterOpen,
                timing.periodFrames(),
                timing,
            )
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun inputSession(
            address: String,
            nonce: UUID,
            target: Int,
            forwardZero: Boolean,
            live: Boolean,
            zeroOnly: Boolean,
            pulse: Boolean,
            sink: OutputStream?,
            stopped: BooleanSupplier,
            ready: HalPortProbe.Ready,
            directSocket: FileDescriptor?,
            afterOpen: HalPortProbe.Ready,
            sourcePeriodFrames: Int,
            phoneTiming: PhoneTiming?,
        ): JSONObject {
            if (
                phoneTiming != null &&
                    (target != phoneTiming.reserveFrames ||
                        sourcePeriodFrames != phoneTiming.periodFrames())
            )
                throw SecurityException()
            require(
                !(target != 2587 &&
                    target != 928 &&
                    target != 464 &&
                    target != 800 &&
                    target != 752 &&
                    target != 768 &&
                    !(phoneTiming != null && target >= 64 && target <= 800))
            )
            val probe = HalPortProbe(nonce, 44100, false, address)
            probe.externalStopped = stopped
            val baseline = probe.snapshot()
            var device: Int = 0
            for (port in baseline!!.ports!!) if (port!!.type === 11 && address == port!!.address) {
                if (device != 0) throw SecurityException("Ambiguous policy input")
                device = port!!.id
            }
            if (device == 0) throw SecurityException("Policy input port missing")
            val journal = Path.of("/data/local/tmp/homepod-policy-input-" + nonce + ".json")
            val document =
                probe.state
                    .snapshot()!!
                    .put("epoch", probe.epoch)
                    .put("registeredAddress", address)
                    .put("zeroOnly", zeroOnly)
                    .put("streamLedger", JSONArray())
            Files.write(
                journal,
                document.toString().toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            Files.setPosixFilePermissions(journal, PosixFilePermissions.fromString("rw-------"))
            probe.ledger =
                StreamOpenLedger(
                    StreamOpenLedger.Journal { entries: MutableList<StreamOpenLedger.Entry?>? ->
                        val values = JSONArray()
                        for (entry in entries!!) values.put(
                            JSONObject()
                                .put("config", entry!!.configId)
                                .put("input", entry!!.input)
                                .put("phase", entry!!.phase!!.name)
                        )
                        document.put("streamLedger", values)
                        val next =
                            journal.resolveSibling(journal.getFileName().toString() + ".next")
                        Files.write(
                            next,
                            document.toString().toByteArray(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                        )
                        Files.setPosixFilePermissions(
                            next,
                            PosixFilePermissions.fromString("rw-------"),
                        )
                        FileChannel.open(next, StandardOpenOption.WRITE).use { channel ->
                            channel.force(true)
                        }
                        Files.move(
                            next,
                            journal,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    },
                    java.util.List.of<StreamOpenLedger.Entry?>(),
                )
            val configs = ArrayList<OwnedHalResources.Config?>()
            var patch: OwnedHalResources.Patch? = null
            val report = JSONObject()
            var tuneAttempted: Boolean = false
            var latencyWindowAttempted: Boolean = false
            val watchdog =
                Executors.newSingleThreadScheduledExecutor(
                    ThreadFactory { r: Runnable? ->
                        val t = Thread(r, "HomePod-policy-input-close")
                        t.setDaemon(true)
                        t
                    }
                )
            val startupTimeout =
                watchdog.schedule(
                    Runnable {
                        probe.stopRequested.set(true)
                        probe.closeStreams()
                    },
                    (if (live) 20 else if (forwardZero) 22 else 15).toLong(),
                    TimeUnit.SECONDS,
                )
            if (forwardZero && !live) {
                val stdinWatcher =
                    Thread(
                        Runnable {
                            try {
                                FileInputStream(FileDescriptor.`in`).use { control ->
                                    control.read()
                                }
                            } catch (ignored: IOException) {} finally {
                                probe.stopRequested.set(true)
                                // The bounded output writer observes the closed/full client pipe.
                                // Keep the input
                                // alive until its owner restores the reserve, then closes it in the
                                // normal
                                // finally.
                            }
                        },
                        "HomePod-zero-client-death",
                    )
                stdinWatcher.setDaemon(true)
                stdinWatcher.start()
            }
            try {
                val mix = probe.configure(4, true, true)
                configs.add(
                    probe
                        .snapshot()!!
                        .configs!!
                        .stream()
                        .filter({ c -> c!!.id === mix })
                        .findFirst()
                        .orElseThrow()
                )
                val deviceConfig = probe.configure(device, true, false)
                configs.add(
                    probe
                        .snapshot()!!
                        .configs!!
                        .stream()
                        .filter({ c -> c!!.id === deviceConfig })
                        .findFirst()
                        .orElseThrow()
                )
                val connected = probe.patch(deviceConfig, mix)
                patch =
                    OwnedHalResources.Patch(
                        connected!!.id,
                        List.of(deviceConfig),
                        List.of(mix),
                    )
                report.put("stream", probe.open(mix, true, connected!!.minimumFrames))
                report.put("targetFillFrames", target)
                afterOpen.run()
                if (phoneTiming != null) {
                    latencyWindowAttempted = true
                    tuneAttempted = latencyWindowAttempted
                    val prepared =
                        HalPortProbe.changePolicyReserve(
                            address,
                            PhonePreparation(phoneTiming).command(),
                        )
                    report.put("tuning", prepared)
                    report.put("latencyWindow", prepared)
                    check(
                        !(!prepared!!.contains("Owned reserve changed: 2587 -> " + target + ";") ||
                            !prepared!!.contains(
                                "Owned latency window changed: 4096 -> " +
                                    phoneTiming.latencyWindowFrames() +
                                    ";"
                            ))
                    ) {
                        "Private phone preparation not confirmed"
                    }
                } else if (target != 2587) {
                    tuneAttempted = true
                    val tuning =
                        HalPortProbe.changePolicyReserve(
                            address,
                            HalPortProbe.reserveOperation(target, false),
                        )
                    report.put("tuning", tuning)
                    check(tuning!!.contains("Owned reserve changed: 2587 -> " + target + ";")) {
                        "Reserve change not confirmed"
                    }
                }
                ready.run()
                if (live) startupTimeout.cancel(false)
                (if (!live || zeroOnly) ZeroPlayback(pulse, sourcePeriodFrames) else null).use {
                    source ->
                    report.put(
                        "mapping",
                        HalQueueMapping.inspectInChild(
                            probe.streams
                                .stream()
                                .map<FrozenHalParcel.Stream?> { stream: HalPortProbe.OpenStream? ->
                                    stream!!.descriptor
                                }
                                .toList(),
                            nonce.toString(),
                            if (live)
                                (if (zeroOnly) ProbeScenario.LIVE_ZERO else ProbeScenario.LIVE)
                            else if (forwardZero) ProbeScenario.POLICY_FORWARD
                            else if (HalPortProbe.diagnosticScenario.get() != null)
                                HalPortProbe.diagnosticScenario.get()!!
                            else ProbeScenario.POLICY_INPUT!!,
                            sink,
                            stopped,
                            directSocket,
                        ),
                    )
                    report.put(
                        "sourceWrittenFrames",
                        if (source == null) 0 else source.writtenFrames(),
                    )
                    report.put(
                        "sourceBufferFrames",
                        if (source == null) 0 else source.bufferFrames(),
                    )
                    report.put("sourceUnderruns", if (source == null) 0 else source.underruns())
                }
            } catch (failure: Exception) {
                report.put("failure", failure.javaClass.getSimpleName() + ": " + failure.message)
            } finally {
                if (latencyWindowAttempted) {
                    try {
                        report.put(
                            "restoreLatencyWindow",
                            HalPortProbe.changePolicyReserve(
                                address,
                                "restore-window-" + phoneTiming!!.latencyWindowFrames(),
                            ),
                        )
                    } catch (failure: Exception) {
                        report.put("latencyWindowRestoreFailure", failure.javaClass.getSimpleName())
                    }
                }
                if (tuneAttempted) {
                    try {
                        report.put(
                            "restore",
                            HalPortProbe.changePolicyReserve(
                                address,
                                HalPortProbe.reserveOperation(target, true),
                            ),
                        )
                    } catch (failure: Exception) {
                        report.put("restoreFailure", failure.javaClass.getSimpleName())
                    }
                }
                probe.closeStreams()
                watchdog.shutdownNow()
                if (probe.streamsClosed()) {
                    if (patch != null && probe.snapshot()!!.patches!!.contains(patch))
                        probe.removePatch(patch.id)
                    for (i in configs.indices.reversed()) {
                        val config = configs.get(i)
                        val current = probe.snapshot()
                        if (
                            current!!.configs!!.contains(config) &&
                                current!!
                                    .patches!!
                                    .stream()
                                    .noneMatch({ p ->
                                        p!!.sources!!.contains(config!!.id) ||
                                            p!!.sinks!!.contains(config!!.id)
                                    })
                        )
                            probe.removeConfig(config!!.id)
                    }
                }
                report
                    .put("streamsClosed", probe.streamsClosed())
                    .put("openReplyUncertain", probe.ledger!!.replyUncertain())
                    .put("resourcesRestoredExactly", probe.snapshot() == baseline)
                val after = probe.snapshot()
                var removed =
                    configs.stream().noneMatch { c: OwnedHalResources.Config? ->
                        after!!.configs!!.stream().anyMatch({ x -> x!!.id === c!!.id })
                    }
                if (patch != null)
                    for (existing in after!!.patches!!) if (existing!!.id === patch.id)
                        removed = false
                report.put(
                    "ownedInputReleased",
                    removed && probe.streamsClosed() && !probe.ledger!!.replyUncertain(),
                )
            }
            return report
        }

        @JvmStatic
        private fun reserveOperation(target: Int, restore: Boolean): String? {
            if (target >= 64 && target <= 800)
                return (if (restore) "restore-reserve-" else "reserve-") + target
            val operation: String? =
                when (target) {
                    928 -> "brief"
                    464 -> "minimum"
                    800 -> "direct"
                    752 -> "direct-752"
                    768 -> "direct-768"
                    96 -> "tiny-period"
                    else -> throw IllegalArgumentException()
                }
            return if (restore) "restore-" + operation else operation
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun changePolicyReserve(address: String?, operation: String?): String? {
            val child =
                ProcessBuilder(
                        "timeout",
                        "-k",
                        "1",
                        "5",
                        "app_process",
                        "/system/bin",
                        "dev.ryuya.homepodbridge.HalRootProbe",
                        address,
                        operation,
                    )
                    .start()
            try {
                if (!child.waitFor(7, TimeUnit.SECONDS))
                    throw IOException("Reserve helper timed out")
                val result =
                    String(
                        child.getInputStream().readNBytes(4096),
                        StandardCharsets.UTF_8,
                    )
                if (child.exitValue() != 0) throw IOException("Reserve helper failed")
                return result.trim { it <= ' ' }
            } finally {
                if (child.isAlive()) child.destroyForcibly()
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun epoch(): String? {
            val query =
                ProcessBuilder("dumpsys", "--pid", "android.hardware.audio.core.IModule/r_submix")
                    .start()
            val pid =
                String(
                        query.getInputStream().readNBytes(128),
                        StandardCharsets.US_ASCII,
                    )
                    .trim { it <= ' ' }
            check(!(!query.waitFor(2, TimeUnit.SECONDS) || !pid.matches("[0-9]{1,7}".toRegex()))) {
                "Module PID unavailable"
            }
            val proc = Path.of("/proc", pid)
            if (
                (TextCompatibility.split(
                        HalPortProbe.readString(proc.resolve("cmdline"))!!,
                        "\u0000",
                    )[0] != "/vendor/bin/hw/audiohalservice.qti") ||
                    !HalPortProbe.readString(proc.resolve("status"))!!.matches(
                        "(?s).*\\nUid:\\s+1041\\s+1041\\s+1041\\s+1041\\s*\\n.*".toRegex()
                    )
            )
                throw SecurityException("Unexpected module process")
            val stat = HalPortProbe.readString(proc.resolve("stat"))
            val fields =
                TextCompatibility.split(
                    stat!!.substring(stat!!.lastIndexOf(')') + 2),
                    "\\s+",
                )
            check(!(fields.size < 20 || !fields[19].matches("[0-9]+".toRegex()))) {
                "Module start time unavailable"
            }
            return pid + ":" + fields[19]
        }

        @JvmStatic
        @Throws(Exception::class)
        fun decode(value: JSONObject, epoch: String?): OwnedHalResources.Snapshot? {
            val ports = ArrayList<OwnedHalResources.Port?>()
            val configs = ArrayList<OwnedHalResources.Config?>()
            val patches = ArrayList<OwnedHalResources.Patch?>()
            var array = value.getJSONArray("ports")
            for (i in 0 until array.length()) {
                val p = array.getJSONObject(i)
                ports.add(
                    OwnedHalResources.Port(
                        p.getInt("id"),
                        p.optInt("deviceType", 0),
                        p.optString("address", ""),
                    )
                )
            }
            array = value.getJSONArray("configs")
            for (i in 0 until array.length()) {
                val c = array.getJSONObject(i)
                configs.add(
                    OwnedHalResources.Config(
                        c.getInt("id"),
                        c.getInt("portId"),
                        c.optInt("handle", 0),
                    )
                )
            }
            array = value.getJSONArray("patches")
            for (i in 0 until array.length()) {
                val p = array.getJSONObject(i)
                patches.add(
                    OwnedHalResources.Patch(
                        p.getInt("id"),
                        HalPortProbe.integers(p.getJSONArray("sources")),
                        HalPortProbe.integers(p.getJSONArray("sinks")),
                    )
                )
            }
            return OwnedHalResources.Snapshot(epoch, ports, configs, patches)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun integers(array: JSONArray): MutableList<Int?> {
            val result = ArrayList<Int?>()
            for (i in 0 until array.length()) result.add(array.getInt(i))
            return result
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun call(binder: IBinder, descriptor: String, method: Int): Parcel? {
            if (descriptor != binder.getInterfaceDescriptor())
                throw SecurityException("Unexpected stream interface")
            val `in` = Parcel.obtain()
            val out = Parcel.obtain()
            try {
                `in`.writeInterfaceToken(descriptor)
                check(binder.transact(method, `in`, out, 0)) { "Stream method unavailable" }
                out.readException()
                return out
            } catch (failure: Exception) {
                out.recycle()
                throw failure
            } finally {
                `in`.recycle()
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun common(stream: HalPortProbe.OpenStream) {
            if (stream.common != null) return
            val type: String =
                "android.hardware.audio.core." + (if (stream.input) "IStreamIn" else "IStreamOut")
            val reply = HalPortProbe.call(stream.binder, type, 1)
            try {
                stream.common = reply!!.readStrongBinder()
                if (
                    stream.common == null ||
                        ("android.hardware.audio.core.IStreamCommon" !=
                            stream!!.common!!.getInterfaceDescriptor())
                )
                    throw SecurityException("Unexpected common interface")
            } finally {
                reply!!.recycle()
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun number(`object`: Any, field: String?, value: Int) {
            val f = `object`.javaClass.getField(field)
            if (f.getType() == Byte::class.javaPrimitiveType) f.setByte(`object`, value.toByte())
            else if (f.getType() == Short::class.javaPrimitiveType)
                f.setShort(`object`, value.toShort())
            else f.setInt(`object`, value)
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun readString(path: Path?): String? {
            return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun rootPreflight() {
            if (Process.myUid() != 0) throw SecurityException("Root-owned transport required")
            val pins =
                arrayOf<Array<String?>?>(
                    arrayOf<String?>(
                        "/vendor/lib64/libaudioserviceexampleimpl.so",
                        "ef1bad7d1ae1d66a6ac6b39a009fba43e00031bb9a3316e435965d4e91564d61",
                    ),
                    arrayOf<String?>(
                        "/vendor/lib64/libnbaio_mono.so",
                        "c363ecab034babaa344163bc4aaf67dfe42945055c433e0640ba555b86d159aa",
                    ),
                )
            for (pin in pins) {
                val file = Path.of(pin!![0])
                if (
                    Files.size(file) > 4194304 ||
                        (HexFormat.of()
                            .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                    .digest(Files.readAllBytes(file))
                            ) != pin!![1])
                )
                    throw SecurityException("Unsupported vendor build")
            }
            val query =
                ProcessBuilder("dumpsys", "android.hardware.audio.core.IModule/r_submix").start()
            val output = query.getInputStream().readNBytes(8193)
            if (
                !query.waitFor(2, TimeUnit.SECONDS) ||
                    query.exitValue() != 0 ||
                    output.size > 8192 ||
                    (String(output, StandardCharsets.UTF_8).replace("\r", "").trim { it <= ' ' } !=
                        "SubmixRoutes:\n <Empty>")
            )
                throw SecurityException("Existing submix route; no allocation allowed")
        }

        @JvmStatic
        fun main(args: Array<String>) {
            if (args.size > 0 && args[0] == "policy-forward") System.setOut(System.err)
            try {
                HalPortProbe.runMain(args)
            } catch (failure: Throwable) {
                println(
                    "{\"probeFailed\":true,\"errorType\":\"" +
                        failure.javaClass.getSimpleName() +
                        "\"}"
                )
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        private fun runMain(args: Array<out String?>) {
            HalPortProbe.rootPreflight()
            if (args.size == 3 && args[0] == "export-audioserver") {
                require(!(UUID.fromString(args[1]).toString() != args[1] || args[2] != "44100"))
                val bytes = Files.readAllBytes(Path.of("/system/bin/audioserver"))
                if (bytes.size > 8388608) throw SecurityException("Unexpected server image size")
                val sha =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                val output = Path.of("/data/local/tmp/homepod-audioserver-" + sha + ".elf")
                if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                    Files.write(
                        output,
                        bytes,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    )
                    Files.setPosixFilePermissions(
                        output,
                        PosixFilePermissions.fromString("r--r--r--"),
                    )
                }
                println(
                    JSONObject()
                        .put("bytes", bytes.size)
                        .put("sha256", sha)
                        .put("readOnlyImage", output.toString())
                )
                return
            }
            if (
                args.size == 3 &&
                    (args[0] == "getter-period" ||
                        args[0] == "getter-period-mixer" ||
                        args[0] == "getter-period-capture" ||
                        args[0]!!.matches("getter-period-step-(?:[1-9]|1[0-9]|2[01])".toRegex()))
            ) {
                require(!(UUID.fromString(args[1]).toString() != args[1] || args[2] != "44100"))
                val result =
                    HalPeriodExperiment.run(
                        args[0] != "getter-period",
                        args[0]!!.endsWith("capture"),
                        if (args[0]!!.startsWith("getter-period-step-"))
                            PhoneTiming(args[0]!!.substring(19).toInt(), 96)
                        else PhoneTiming.fastest()!!,
                    )
                println(result)
                System.out.flush()
                if (result!!.optBoolean("resourcesRestoredExactly")) System.exit(0)
                return
            }
            if (args.size == 3 && args[0] == "output-period") {
                require(!(UUID.fromString(args[1]).toString() != args[1] || args[2] != "44100"))
                val result = OutputPeriodProbe.run()
                println(result)
                System.out.flush()
                if (result!!.optBoolean("resourcesRestoredExactly")) System.exit(0)
                return
            }
            if (
                args.size == 3 &&
                    (args[0] == "policy-input" ||
                        args[0] == "policy-route" ||
                        args[0] == "policy-capture" ||
                        args[0] == "policy-capture-fast" ||
                        args[0] == "policy-capture-minimum" ||
                        args[0] == "policy-capture-smaller" ||
                        args[0] == "policy-capture-poll" ||
                        args[0] == "policy-capture-half" ||
                        args[0] == "policy-forward")
            ) {
                require(!(UUID.fromString(args[1]).toString() != args[1] || args[2] != "44100"))
                if (args[0] == "policy-capture-poll")
                    HalPortProbe.diagnosticScenario.set(ProbeScenario.POLICY_POLL)
                if (args[0] == "policy-capture-half")
                    HalPortProbe.diagnosticScenario.set(ProbeScenario.POLICY_HALF)
                val report =
                    PolicyInputProbe.run(
                        args[0] != "policy-input",
                        UUID.fromString(args[1]),
                        args[0]!!.startsWith("policy-capture") || args[0] == "policy-forward",
                        if (args[0] == "policy-capture-fast") 928
                        else if (args[0] == "policy-capture-minimum") 464
                        else if (
                            java.util.Set.of<String?>(
                                    "policy-capture-smaller",
                                    "policy-forward",
                                    "policy-capture-poll",
                                    "policy-capture-half",
                                )
                                .contains(args[0])
                        )
                            800
                        else 2587,
                        args[0] == "policy-forward",
                    )
                println(report)
                System.out.flush()
                val direct = report.optJSONObject("directCapture")
                // ActivityThread's platform helpers can outlive main. Exit only after confirmed
                // cleanup,
                // never while the stream owner might still hold a live or uncertain HAL allocation.
                if (
                    report.optBoolean("resourcesRestoredExactly") &&
                        (direct == null ||
                            (direct.optBoolean("streamsClosed") &&
                                !direct.optBoolean("openReplyUncertain") &&
                                direct.optBoolean("resourcesRestoredExactly")))
                )
                    System.exit(0)
                return
            }
            if (args.size == 3 && args[0] == "metrics") {
                require(!(UUID.fromString(args[1]).toString() != args[1] || args[2] != "44100"))
                val current = HalPortProbe.epoch()
                val proc =
                    Path.of(
                        "/proc",
                        TextCompatibility.split(current!!, ":")[0],
                    )
                val fds: Long
                val threads: Long
                Files.list(proc.resolve("fd")).use { files ->
                    fds = files.count()
                }
                Files.list(proc.resolve("task")).use { tasks ->
                    threads = tasks.count()
                }
                if (current != HalPortProbe.epoch())
                    throw SecurityException("Module changed during observation")
                println(
                    JSONObject()
                        .put("moduleEpoch", current)
                        .put("openFdCount", fds)
                        .put("threadCount", threads)
                        .put("readOnly", true)
                )
                return
            }
            if (args.size == 3 && args[0] == "stop") {
                val nonce = UUID.fromString(args[1]).toString()
                require(!(nonce != args[1] || args[2] != "44100"))
                val matches = ArrayList<Path?>()
                Files.list(Path.of("/proc")).use { processes ->
                    for (proc in
                        processes
                            .filter { p: Path? ->
                                p!!.getFileName().toString().matches("[1-9][0-9]{0,6}".toRegex())
                            }
                            .toList()) {
                        try {
                            if (
                                OwnedProbeProcess.matches(
                                    HalPortProbe.readString(proc.resolve("cmdline"))!!,
                                    HalPortProbe.readString(proc.resolve("status"))!!,
                                    nonce,
                                )
                            )
                                matches.add(proc)
                        } catch (exited: Exception) {
                            /* Unrelated process exited during inventory. */
                        }
                    }
                }
                if (matches.size != 1)
                    throw SecurityException("Expected exactly one owned prototype process")
                val proc = matches.get(0)
                val command = HalPortProbe.readString(proc!!.resolve("cmdline"))
                val identity = HalPortProbe.readString(proc!!.resolve("stat"))
                if (OwnedProbeProcess.matchesClosedCycle(command!!, nonce)) {
                    val journal = Path.of("/data/local/tmp/homepod-policy-input-" + nonce + ".json")
                    if (
                        Files.isSymbolicLink(journal) ||
                            Files.size(journal) > 65536 ||
                            ((Files.getAttribute(journal, "unix:uid", LinkOption.NOFOLLOW_LINKS)
                                    as Number)
                                .toInt() != 0)
                    )
                        throw SecurityException("Invalid closed capture journal")
                    val saved = JSONObject(HalPortProbe.readString(journal))
                    val probe = HalPortProbe(UUID.fromString(nonce), 44100, false)
                    val current = probe.snapshot()
                    if (
                        !saved.getBoolean("zeroOnly") ||
                            (saved.getString("epoch") != probe.epoch) ||
                            current!!
                                .ports!!
                                .stream()
                                .anyMatch({ p ->
                                    p!!.address.equals(saved.optString("registeredAddress"))
                                })
                    )
                        throw SecurityException("Capture policy still owned")
                    val entries = saved.getJSONArray("streamLedger")
                    if (entries.length() == 0)
                        throw SecurityException("Missing closed capture proof")
                    for (i in 0 until entries.length()) {
                        val entry = entries.getJSONObject(i)
                        if (
                            entry.getString("phase") != "CLOSED" ||
                                current!!
                                    .configs!!
                                    .stream()
                                    .anyMatch({ c -> c!!.id === entry.optInt("config") })
                        )
                            throw SecurityException("Capture input still owned")
                    }
                }
                if (
                    !OwnedProbeProcess.matches(
                        HalPortProbe.readString(proc!!.resolve("cmdline"))!!,
                        HalPortProbe.readString(proc!!.resolve("status"))!!,
                        nonce,
                    )
                )
                    throw SecurityException()
                val now = HalPortProbe.readString(proc!!.resolve("stat"))
                if (
                    TextCompatibility.split(
                            identity!!.substring(identity!!.lastIndexOf(')') + 2),
                            "\\s+",
                        )[19] !=
                        TextCompatibility.split(
                                now!!.substring(now!!.lastIndexOf(')') + 2),
                                "\\s+",
                            )[19]
                )
                    throw SecurityException("Helper identity changed")
                Process.sendSignal(proc!!.getFileName().toString().toInt(), 15)
                println("OWNED_PROBE_STOP_SENT")
                return
            }
            val scenario: ProbeScenario? =
                if (args.size == 0) null else ProbeScenario.forOperation(args[0])
            require(
                !((args.size != 2 && args.size != 3) ||
                    !(args[0] == "create" ||
                        args[0] == "recover" ||
                        args[0] == "configs" ||
                        args[0] == "configs-fast" ||
                        scenario != null))
            )
            val nonce = UUID.fromString(args[1])
            require(nonce.toString() == args[1])
            val journal = Path.of("/data/local/tmp/homepod-direct-" + nonce + ".json")
            val probe =
                HalPortProbe(
                    nonce,
                    if (args.size == 3) args[2]!!.toInt() else 44100,
                    args[0] == "configs-fast",
                )
            val saved: JSONObject?
            if (args[0] != "recover") {
                saved =
                    probe.state
                        .snapshot()!!
                        .put("epoch", probe.epoch)
                        .put("streamLedger", JSONArray())
                        .put("zeroOnly", scenario != null && scenario.zeroFrames > 0)
                Files.write(
                    journal,
                    saved.toString().toByteArray(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                )
                Files.setPosixFilePermissions(journal, PosixFilePermissions.fromString("rw-------"))
            } else {
                if (
                    Files.isSymbolicLink(journal) ||
                        Files.size(journal) > 65536 ||
                        ((Files.getAttribute(journal, "unix:uid", LinkOption.NOFOLLOW_LINKS)
                                as Number)
                            .toInt() != Process.myUid())
                )
                    throw SecurityException("Invalid ownership journal")
                saved = JSONObject(HalPortProbe.readString(journal))
            }
            if (probe.epoch != saved.getString("epoch")) {
                println("{\"oldModuleGone\":true,\"mutations\":0}")
                return
            }
            // Legacy journals cannot prove that an open reply was delivered. Never reuse them
            // to infer an empty stream list (the original open-only failure did exactly that).
            check(!(args[0] == "recover" && !saved.has("streamLedger"))) {
                "Legacy journal has unknown stream state; automatic cleanup refused"
            }
            val recovered = ArrayList<StreamOpenLedger.Entry?>()
            val entries = saved.optJSONArray("streamLedger")
            if (entries != null)
                for (i in 0 until entries.length()) {
                    val e = entries.getJSONObject(i)
                    recovered.add(
                        StreamOpenLedger.Entry(
                            e.getInt("config"),
                            e.getBoolean("input"),
                            StreamOpenLedger.Phase.valueOf(e.getString("phase")),
                        )
                    )
                }
            val document: JSONObject? = saved
            probe.ledger =
                StreamOpenLedger(
                    StreamOpenLedger.Journal { values: MutableList<StreamOpenLedger.Entry?>? ->
                        val array = JSONArray()
                        for (e in values!!) array.put(
                            JSONObject()
                                .put("config", e!!.configId)
                                .put("input", e!!.input)
                                .put("phase", e!!.phase!!.name)
                        )
                        document!!.put("streamLedger", array)
                        val next =
                            journal.resolveSibling(journal.getFileName().toString() + ".next")
                        Files.write(
                            next,
                            document.toString().toByteArray(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                        )
                        Files.setPosixFilePermissions(
                            next,
                            PosixFilePermissions.fromString("rw-------"),
                        )
                        FileChannel.open(next, StandardOpenOption.WRITE).use { channel ->
                            channel.force(true)
                        }
                        Files.move(
                            next,
                            journal,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    },
                    recovered,
                )
            val baseline = HalPortProbe.decode(saved, probe.epoch)
            val owner = OwnedHalResources(probe, baseline!!, probe.tag)
            val watchdog =
                Executors.newSingleThreadScheduledExecutor(
                    ThreadFactory { r: Runnable? ->
                        val t = Thread(r)
                        t.setDaemon(true)
                        t
                    }
                )
            watchdog.schedule(
                Runnable {
                    probe.stopRequested.set(true)
                    probe.closeStreams()
                    owner.cleanup()
                },
                15,
                TimeUnit.SECONDS,
            )
            val created = JSONArray()
            val minimumFrames = JSONArray()
            val opened = JSONArray()
            var mapping: JSONObject? = null
            var failure: Exception? = null
            val result: OwnedHalResources.Result?
            try {
                if (args[0] != "recover") {
                    val output = probe.createVirtualPort(143)
                    val input = probe.createVirtualPort(11)
                    created.put(output)
                    created.put(input)
                    if (args[0]!!.startsWith("configs") || scenario != null) {
                        val outMix = probe.configure(3, false, true)
                        val inMix = probe.configure(4, true, true)
                        val outDevice = probe.configure(output, false, false)
                        val inDevice = probe.configure(input, true, false)
                        val outPatch = probe.patch(outMix, outDevice)
                        val inPatch = probe.patch(inDevice, inMix)
                        minimumFrames.put(outPatch!!.minimumFrames)
                        minimumFrames.put(inPatch!!.minimumFrames)
                        if (scenario != null) {
                            opened.put(probe.open(outMix, false, outPatch!!.minimumFrames))
                            opened.put(probe.open(inMix, true, inPatch!!.minimumFrames))
                            mapping =
                                HalQueueMapping.inspectInChild(
                                    probe.streams
                                        .stream()
                                        .map<FrozenHalParcel.Stream?> {
                                            stream: HalPortProbe.OpenStream? ->
                                            stream!!.descriptor
                                        }
                                        .toList(),
                                    nonce.toString(),
                                    scenario,
                                )
                        }
                    }
                }
            } catch (error: Exception) {
                failure = error
            } finally {
                probe.closeStreams()
                result = owner.cleanup()
                watchdog.shutdownNow()
            }
            val report =
                JSONObject()
                    .put("created", created)
                    .put("cleanupComplete", result!!.complete)
                    .put("oldInstanceGone", result!!.oldInstanceGone)
                    .put("noStreamOpenAttempted", !probe.ledger!!.attempted())
                    .put("openReplyUncertain", probe.ledger!!.replyUncertain())
                    .put("streams", opened)
                    .put("streamsClosed", probe.streamsClosed())
                    .put("sampleRate", probe.rate)
                    .put("minimumFrames", minimumFrames)
                    .put("fastRequested", probe.fast)
                    .put("cleanupErrors", JSONArray(result!!.errors))
            report.put("resourcesRestoredExactly", probe.snapshot() == baseline)
            if (mapping != null) report.put("mapping", mapping)
            if (failure != null)
                report.put("failure", failure.javaClass.getSimpleName() + ": " + failure.message)
            println(report)
            check(result!!.complete) { "Owned port cleanup incomplete" }
            if (failure != null) throw failure
        }

        @JvmStatic
        private fun streamsCount(probe: HalPortProbe): Int {
            return probe.streams.size
        }
    }
}
