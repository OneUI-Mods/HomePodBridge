package dev.ryuya.homepodbridge

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.Signature
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.Binder
import android.os.HandlerThread
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import android.os.Parcelable
import android.os.Process
import android.os.RemoteException
import android.util.Log
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.Synchronized
import kotlin.math.log
import kotlin.math.max

/* Runs only inside SystemUI. Captures USAGE_MEDIA for one package, never a microphone. */
class SystemAudioBridge(context: Context, modulePath: String?) : Binder() {
    private val context: Context
    private val moduleSignatures: Array<Signature?>
    private var stream: SystemAudioBridge.Stream? = null
    private var external: SystemAudioBridge.ExternalSession? = null

    init {
        this.context = context
        val archive =
            context
                .getPackageManager()
                .getPackageArchiveInfo(modulePath!!, PackageManager.GET_SIGNING_CERTIFICATES)
        check(!(archive == null || archive.signingInfo == null)) { "Module signature unavailable" }
        moduleSignatures = archive!!.signingInfo!!.getApkContentsSigners()
        attachInterface(null, RootProtocol.DESCRIPTOR)
    }

    @Synchronized
    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            reply!!.writeString(RootProtocol.DESCRIPTOR)
            return true
        }
        if (
            code != RootProtocol.OPEN &&
                code != RootProtocol.OPEN_CONFIGURED &&
                code != RootProtocol.OPEN_TIMED &&
                code != RootProtocol.CAPTURE_TIMING &&
                code != RootProtocol.OPEN_SILENT_PROBE &&
                code != RootProtocol.OPEN_EXTERNAL &&
                code != RootProtocol.VOLUME_ENDPOINT &&
                code != RootProtocol.CLOSE
        )
            return super.onTransact(code, data, reply, flags)
        data.enforceInterface(RootProtocol.DESCRIPTOR)
        try {
            val app = context.getPackageManager().getApplicationInfo(RootProtocol.PACKAGE, 0)
            val installed =
                context
                    .getPackageManager()
                    .getPackageInfo(RootProtocol.PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            if (
                Binder.getCallingUid() != app.uid ||
                    installed.signingInfo == null ||
                    !moduleSignatures.contentEquals(
                        installed!!.signingInfo!!.getApkContentsSigners()
                    )
            )
                throw SecurityException("Caller is not the installed module")
        } catch (e: NameNotFoundException) {
            throw SecurityException("Module not installed")
        }
        val client = data.readStrongBinder()
        requireNotNull(client) { "Client lifetime token required" }
        val identity = Binder.clearCallingIdentity()
        try {
            if (code == RootProtocol.CAPTURE_TIMING) {
                reply!!.writeNoException()
                reply!!.writeLongArray(
                    if (stream != null && stream!!.client == client) stream!!.timing else null
                )
                return true
            }
            if (code == RootProtocol.VOLUME_ENDPOINT) {
                val callback = data.readStrongBinder()
                if (
                    stream != null &&
                        stream!!.client == client &&
                        stream!!.wholeDevice &&
                        callback != null
                )
                    stream!!.volumeEndpoint(callback)
                if (external != null && external!!.client == client && callback != null)
                    external!!.endpoint(callback)
                reply!!.writeNoException()
                return true
            }
            if (code == RootProtocol.CLOSE) {
                if (external != null && external!!.client == client) {
                    external!!.close()
                    external = null
                }
                if (stream != null && stream!!.client == client) {
                    stream!!.close()
                    stream = null
                }
                reply!!.writeNoException()
                return true
            }
            if (code == RootProtocol.OPEN_EXTERNAL) {
                val whole = data.readBoolean()
                if (stream != null) {
                    stream!!.close()
                    stream = null
                }
                if (external != null) external!!.close()
                external = ExternalSession(client, whole)
                reply!!.writeNoException()
                return true
            }
            val targetPackage = data.readString()
            require(
                !(targetPackage == null ||
                    targetPackage.isEmpty() ||
                    targetPackage == RootProtocol.PACKAGE)
            ) {
                "Invalid media package"
            }
            val silentProbe: Boolean = code == RootProtocol.OPEN_SILENT_PROBE
            val configured: Boolean =
                code == RootProtocol.OPEN_CONFIGURED ||
                    code == RootProtocol.OPEN_TIMED ||
                    silentProbe
            val wholeDevice: Boolean = configured && data.readBoolean()
            val blockMs: Int = if (configured) data.readInt() else 10
            val fast: Boolean =
                (code == RootProtocol.OPEN_TIMED || silentProbe) && data.readBoolean()
            require(!(silentProbe && !wholeDevice)) { "Probe requires whole-device capture rules" }
            val targetUid: Int =
                if (wholeDevice) -1
                else context.getPackageManager().getApplicationInfo(targetPackage, 0).uid
            val settings = StreamSettings(wholeDevice, 150, blockMs, 1500)
            if (stream != null) {
                stream!!.close()
                stream = null
            }
            if (external != null) {
                external!!.close()
                external = null
            }
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                stream =
                    Stream(
                        client,
                        targetUid,
                        appUid(),
                        pipe[1],
                        settings,
                        code == RootProtocol.OPEN_TIMED || silentProbe,
                        fast,
                        silentProbe,
                    )
                reply!!.writeNoException()
                pipe[0].writeToParcel(reply!!, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                reply!!.writeString(stream!!.registration)
            } catch (e: Throwable) {
                pipe[0].close()
                pipe[1].close()
                throw IllegalStateException("System audio initialization failed", e)
            }
        } catch (e: Throwable) {
            reply!!.writeException(IllegalStateException("System audio capture is unavailable"))
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        return true
    }

    @Throws(NameNotFoundException::class)
    private fun appUid(): Int {
        return context.getPackageManager().getApplicationInfo(RootProtocol.PACKAGE, 0).uid
    }

    private inner class ExternalSession(client: IBinder, whole: Boolean) : DeathRecipient {
        @JvmField val client: IBinder
        @JvmField val whole: Boolean
        @JvmField var endpoint: SystemVolumeEndpoint? = null
        @JvmField var closed: Boolean = false

        init {
            this.client = client
            this.whole = whole
            client.linkToDeath(this, 0)
        }

        @Synchronized
        fun endpoint(callback: IBinder?) {
            if (closed || !whole || endpoint != null) return
            try {
                endpoint = SystemVolumeEndpoint(context, callback!!)
            } catch (error: Throwable) {
                XposedBridge.log("HomePod Bridge: external volume unavailable")
            }
        }

        @Synchronized
        fun close() {
            if (closed) return
            closed = true
            if (endpoint != null) endpoint!!.close()
            try {
                client.unlinkToDeath(this, 0)
            } catch (alreadyDead: RuntimeException) {}
        }

        override fun binderDied() {
            close()
        }
    }

    private inner class Stream(
        client: IBinder,
        uid: Int,
        bridgeUid: Int,
        writePipe: ParcelFileDescriptor,
        settings: StreamSettings,
        timed: Boolean,
        fast: Boolean,
        silentProbe: Boolean,
    ) : DeathRecipient {
        @JvmField val client: IBinder
        @JvmField val policy: Any
        @JvmField val registration: String?
        @JvmField val probeSource: SilentProbeSource?
        @JvmField val record: AudioRecord
        @JvmField val writePipe: ParcelFileDescriptor
        @JvmField val closed: AtomicBoolean = AtomicBoolean()
        @JvmField val blockSamples: Int
        @JvmField val wholeDevice: Boolean
        @JvmField val timed: Boolean
        @JvmField val policyCallbacks: HandlerThread
        @JvmField @Volatile var timing: LongArray? = null
        private var captureRequest: Int = 0
        private var volumeEndpoint: SystemVolumeEndpoint? = null

        init {
            this.client = client
            this.writePipe = writePipe
            this.wholeDevice = settings.wholeDevice
            this.timed = timed
            blockSamples = settings.samplesPerRead()
            val loader = context.getClassLoader()
            val rule =
                XposedHelpers.newInstance(
                    XposedHelpers.findClass(
                        "android.media.audiopolicy.AudioMixingRule\$Builder",
                        loader,
                    )
                )
            if (settings.wholeDevice) {
                XposedHelpers.callMethod(rule, "setTargetMixRole", 0) // MIX_ROLE_PLAYERS
                XposedHelpers.callMethod(rule, "excludeMixRule", 4, bridgeUid)
                for (usage in
                    intArrayOf(
                        AudioAttributes.USAGE_VOICE_COMMUNICATION,
                        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
                    )) {
                    XposedHelpers.callMethod(
                        rule,
                        "excludeRule",
                        android.media.AudioAttributes.Builder().setUsage(usage).build(),
                        1,
                    )
                }
            } else {
                XposedHelpers.callMethod(
                    rule,
                    "addRule",
                    android.media.AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build(),
                    1,
                )
                XposedHelpers.callMethod(rule, "addMixRule", 4, uid) // RULE_MATCH_UID
            }
            val mix =
                XposedHelpers.newInstance(
                    XposedHelpers.findClass("android.media.audiopolicy.AudioMix\$Builder", loader),
                    XposedHelpers.callMethod(rule, "build"),
                )
            val format =
                android.media.AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            XposedHelpers.callMethod(mix, "setFormat", format)
            XposedHelpers.callMethod(
                mix,
                "setRouteFlags",
                2,
            ) // LOOP_BACK, without RENDER: redirect this media stream
            val audioMix = XposedHelpers.callMethod(mix, "build")
            val builder =
                XposedHelpers.newInstance(
                    XposedHelpers.findClass(
                        "android.media.audiopolicy.AudioPolicy\$Builder",
                        loader,
                    ),
                    context,
                )
            val manager = context.getSystemService<AudioManager?>(AudioManager::class.java)
            policyCallbacks = HandlerThread("HomePod-policy", Process.THREAD_PRIORITY_AUDIO)
            policyCallbacks.start()
            var registeredPolicy: Any? = null
            try {
                XposedHelpers.callMethod(builder, "setLooper", policyCallbacks.getLooper())
                XposedHelpers.callMethod(builder, "addMix", audioMix)
                registeredPolicy = XposedHelpers.callMethod(builder, "build")
                val result: Int =
                    XposedHelpers.callMethod(manager, "registerAudioPolicy", registeredPolicy)
                        as Int
                check(result == 0) { "Policy registration failed" }
            } catch (e: Throwable) {
                if (registeredPolicy != null) {
                    try {
                        XposedHelpers.callMethod(manager, "unregisterAudioPolicy", registeredPolicy)
                    } catch (ignored: Throwable) {}
                }
                policyCallbacks.quitSafely()
                throw IllegalStateException("Policy initialization failed", e)
            }
            policy = registeredPolicy
            probeSource = if (silentProbe) SilentProbeSource() else null
            var registeredAddress: String? = null
            try {
                registeredAddress = XposedHelpers.callMethod(audioMix, "getRegistration") as String?
            } catch (ignored: Throwable) {
                // Optional inspection must not prevent compatible capture on other framework
                // versions.
            }
            registration = registeredAddress
            var created: AudioRecord? = null
            try {
                if (fast) {
                    // Request a smaller FAST sink only for this registered mix; the audio server
                    // has final
                    // say.
                    try {
                        val address: String? =
                            XposedHelpers.callStaticMethod(
                                policy.javaClass,
                                "addressForTag",
                                audioMix,
                            ) as String?
                        val attributes = android.media.AudioAttributes.Builder()
                        XposedHelpers.callMethod(attributes, "setInternalCapturePreset", 8)
                        XposedHelpers.callMethod(attributes, "addTag", address)
                        XposedHelpers.callMethod(attributes, "addTag", "fixedVolume")
                        val inputFormat =
                            android.media.AudioFormat.Builder(format)
                                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                                .build()
                        val flagsClass =
                            XposedHelpers.findClass(
                                "android.media.audio.common.AudioInputFlags",
                                loader,
                            )
                        val fastFlag: Int =
                            1 shl XposedHelpers.getStaticIntField(flagsClass, "FAST")
                        created =
                            XposedHelpers.newInstance(
                                AudioRecord::class.java,
                                attributes.build(),
                                inputFormat,
                                blockSamples * 2,
                                AudioManager.AUDIO_SESSION_ID_GENERATE,
                                context,
                                0,
                                fastFlag,
                            ) as AudioRecord?
                        if (created!!.getState() != AudioRecord.STATE_INITIALIZED) {
                            created!!.release()
                            created = null
                        }
                        captureRequest = if (created != null) 1 else 2
                    } catch (ignored: Throwable) {
                        if (created != null) created.release()
                        created = null
                        captureRequest = 2
                    }
                }
                if (created == null) {
                    // The builder overload can honor a smaller allocation on newer compatible
                    // frameworks.
                    try {
                        created =
                            XposedHelpers.callMethod(
                                policy,
                                "createAudioRecordSink",
                                audioMix,
                                android.media.AudioRecord.Builder()
                                    .setBufferSizeInBytes(blockSamples * 2),
                            ) as AudioRecord?
                    } catch (ignored: Throwable) {
                        created =
                            XposedHelpers.callMethod(policy, "createAudioRecordSink", audioMix)
                                as AudioRecord?
                    }
                }
                check(!(created == null || created.getState() != AudioRecord.STATE_INITIALIZED)) {
                    "Audio sink unavailable"
                }
                record = created
                client.linkToDeath(this, 0)
                record.startRecording()
                check(record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    "Recording failed"
                }
            } catch (e: Throwable) {
                if (created != null) created.release()
                try {
                    client.unlinkToDeath(this, 0)
                } catch (ignored: RuntimeException) {}
                try {
                    XposedHelpers.callMethod(manager, "unregisterAudioPolicy", policy)
                } catch (ignored: Throwable) {}
                policyCallbacks.quitSafely()
                throw IllegalStateException("System audio initialization failed", e)
            }
            val thread = Thread(Runnable { this.pump() }, "HomePod-system-audio")
            thread.setDaemon(true)
            thread.start()
            if (probeSource != null) probeSource.start()
        }

        fun pump() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (ignored: RuntimeException) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            }
            try {
                AutoCloseOutputStream(writePipe).use { output ->
                    val channel = (output as FileOutputStream).getChannel()
                    val capture =
                        PcmCaptureReader(
                            blockSamples * 2,
                            timed,
                            PcmCaptureReader.Reader { buffer: ByteBuffer?, length: Int, mode: Int ->
                                record.read(buffer!!, length, mode)
                            },
                        )
                    val bytes = capture.buffer
                    val timestamp = AudioTimestamp()
                    val header = ByteBuffer.allocateDirect(16)
                    val packet = arrayOf<ByteBuffer?>(header, bytes)
                    var maxReadUs: Long = 0
                    var maxWriteUs: Long = 0
                    var latestAgeUs: Long = -1
                    var ageObservedAt: Long = 0
                    var readFrames: Long = 0
                    var nextTimestamp: Long = System.nanoTime() + 1000000000L
                    var observations: Int = 0
                    while (!closed.get()) {
                        val readBegin = System.nanoTime()
                        val count = capture.read()
                        val readEnd = System.nanoTime()
                        if (count < 0) break
                        if (count == 0) continue
                        if (count % 4 != 0) break
                        readFrames += (count / 4).toLong()
                        bytes.position(0).limit(count)
                        if (timed) {
                            header.clear()
                            header.putInt(PcmPipe.MAGIC).putInt(count).putLong(readEnd).flip()
                            while (
                                (header.hasRemaining() || bytes.hasRemaining()) && !closed.get()
                            ) channel.write(packet)
                        } else {
                            while (bytes.hasRemaining() && !closed.get()) channel.write(bytes)
                        }
                        val writeEnd = System.nanoTime()
                        val readUs: Long = (readEnd - readBegin) / 1000
                        val writeUs: Long = (writeEnd - readEnd) / 1000
                        maxReadUs = max(maxReadUs, readUs)
                        maxWriteUs = max(maxWriteUs, writeUs)
                        if (System.nanoTime() >= nextTimestamp) {
                            // Optional diagnostics must never interrupt capture when a sink lacks
                            // timestamps.
                            try {
                                val result =
                                    record.getTimestamp(
                                        timestamp,
                                        AudioTimestamp.TIMEBASE_MONOTONIC,
                                    )
                                if (result == 0) {
                                    latestAgeUs =
                                        ((System.nanoTime() - timestamp.nanoTime +
                                            (timestamp.framePosition - readFrames) * 1000000000L /
                                                44100) / 1000)
                                    ageObservedAt = System.nanoTime()
                                    if (observations < 5)
                                        Log.i(
                                            "HomePodBridgeCapture",
                                            "latestFrameAgeMicros=" + latestAgeUs,
                                        )
                                } else latestAgeUs = -1
                            } catch (ignored: RuntimeException) {
                                latestAgeUs = -1
                            }
                            observations++
                            nextTimestamp = System.nanoTime() + 1000000000L
                            timing =
                                longArrayOf(
                                    1,
                                    latestAgeUs,
                                    ageObservedAt,
                                    readUs,
                                    maxReadUs,
                                    writeUs,
                                    maxWriteUs,
                                    (record.getBufferSizeInFrames()).toLong(),
                                    (blockSamples / 2).toLong(),
                                    (captureRequest).toLong(),
                                    if (probeSource == null) 0 else probeSource.writtenFrames(),
                                )
                        }
                    }
                }
            } catch (ignored: IOException) {
                // Pipe closure or process death ends this session without affecting SystemUI.
            } catch (ignored: RuntimeException) {} finally {
                close()
                record.release()
                try {
                    XposedHelpers.callMethod(
                        context.getSystemService<AudioManager?>(AudioManager::class.java),
                        "unregisterAudioPolicy",
                        policy,
                    )
                } catch (ignored: Throwable) {}
                policyCallbacks.quitSafely()
                try {
                    client.unlinkToDeath(this, 0)
                } catch (ignored: RuntimeException) {}
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            if (probeSource != null) probeSource.close()
            synchronized(this) {
                if (volumeEndpoint != null) {
                    volumeEndpoint!!.close()
                    volumeEndpoint = null
                }
            }
            try {
                record.stop()
            } catch (ignored: RuntimeException) {}
            try {
                writePipe.close()
            } catch (ignored: IOException) {}
        }

        override fun binderDied() {
            close()
        }

        @Synchronized
        fun volumeEndpoint(callback: IBinder?) {
            if (closed.get()) return
            if (volumeEndpoint == null) {
                try {
                    volumeEndpoint = SystemVolumeEndpoint(context, callback!!)
                    XposedBridge.log("HomePod Bridge: system volume endpoint ready")
                } catch (e: Throwable) {
                    XposedBridge.log(
                        ("HomePod Bridge: system volume endpoint unavailable (" +
                            e.javaClass.getSimpleName() +
                            ")")
                    )
                    XposedBridge.log(e)
                }
            }
        }
    }
}
