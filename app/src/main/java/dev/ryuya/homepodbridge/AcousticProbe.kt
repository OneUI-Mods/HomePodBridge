package dev.ryuya.homepodbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder.AudioSource
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Base64
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

/* Explicit DUMP-only acoustic diagnostic. Mic samples are never forwarded or stored on disk. */
class AcousticProbe(
    context: Context,
    volume: Int,
    paired: Boolean,
    status: Consumer<String?>,
    finishedCallback: Runnable,
) : AutoCloseable {
    private val context: Context
    private val volume: Int
    private val paired: Boolean
    private var phone: PhoneAcousticReference? = null
    private val status: Consumer<String?>
    private val finishedCallback: Runnable
    private val main: Handler = Handler(Looper.getMainLooper())
    private val stopping: AtomicBoolean = AtomicBoolean()
    private val samples: AcousticRecording =
        AcousticRecording(AcousticProbe.MIC_RATE * AcousticProbe.MAX_SECONDS)
    private val anchors: ArrayList<LongArray?> = ArrayList<LongArray?>()
    private val markers: LongArray = LongArray(AcousticSignal.COUNT)
    private val markerStages: Array<LongArray?> = arrayOfNulls<LongArray>(AcousticSignal.COUNT)
    @Volatile private var lifetime: IBinder? = null
    @Volatile private var armed: Boolean = false
    @Volatile private var finished: Boolean = false
    @Volatile private var sampleCount: Int = 0
    @Volatile private var failure: String? = null
    private var armedAt: Long = 0
    private var renderedFrames: Long = 0
    private var pending: Int = -1
    private var pendingOffset: Int = 0
    private var source: Int = 0
    private var startedAt: Long = 0
    private var recording: Thread? = null

    init {
        require(!(volume < 1 || volume > AcousticProbe.MAX_DIAGNOSTIC_VOLUME)) {
            "Diagnostic volume must be 1.." + AcousticProbe.MAX_DIAGNOSTIC_VOLUME
        }
        this.context = context
        this.paired = paired
        this.volume = volume
        this.status = status
        this.finishedCallback = finishedCallback
    }

    fun bind(ownedLifetime: IBinder?) {
        lifetime = ownedLifetime
        AcousticProbe.current.set(this)
    }

    fun connected(success: Boolean) {
        if (!success) {
            failure = "Receiver connection failed"
            stop()
            return
        }
        if (!ownsConnection() || !BridgeState.connected) {
            failure = "Owned receiver session missing"
            stop()
            return
        }
        recording = Thread(Runnable { this.record() }, "HomePod-acoustic-mic")
        recording!!.start()
    }

    private fun ownsConnection(): Boolean {
        return lifetime != null &&
            BridgeState.capture != null &&
            BridgeState.capture!!.lifetime() === lifetime
    }

    // Invoked only after the dedicated zero-only capture record is cleared, on its capture worker.
    fun render(pcm: ByteBuffer, bytes: Int) {
        pending = -1
        if (!armed || stopping.get() || System.nanoTime() < armedAt) return
        val frames: Int = bytes / 4
        for (burst in 0 until AcousticSignal.COUNT) {
            val start = AcousticSignal.firstFrame(burst)
            val end: Long = start + AcousticSignal.TONE_FRAMES
            if (start >= renderedFrames && start < renderedFrames + frames) {
                pending = burst
                pendingOffset = (start - renderedFrames).toInt()
            }
            for (frame in 0 until frames) {
                val at: Long = renderedFrames + frame
                if (at >= start && at < end) {
                    val value = AcousticSignal.sample(burst, (at - start).toInt())
                    pcm.putShort(frame * 4, value)
                    pcm.putShort(frame * 4 + 2, value)
                }
            }
        }
        renderedFrames += frames.toLong()
    }

    fun accepted() {
        if (pending >= 0) {
            synchronized(markers) {
                markerStages[pending] = NativeSender.pipeline()
                markers[pending] =
                    System.nanoTime() + pendingOffset * 1000000000L / AcousticSignal.RATE
            }
            pending = -1
        }
    }

    private fun record() {
        var mic: AudioRecord? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            if (
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
            )
                throw SecurityException("Microphone permission missing")
            val unprocessed: Boolean =
                ("true" ==
                    context
                        .getSystemService<AudioManager?>(AudioManager::class.java)
                        .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))
            source = if (unprocessed) AudioSource.UNPROCESSED else AudioSource.VOICE_RECOGNITION
            val minimum =
                AudioRecord.getMinBufferSize(
                    AcousticProbe.MIC_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            check(minimum > 0) { "Microphone format unavailable" }
            if (paired) {
                phone = PhoneAcousticReference(context)
                phone!!.start()
            }
            mic =
                android.media.AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setSampleRate(AcousticProbe.MIC_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(max(minimum, AcousticProbe.MIC_RATE * 2 / 5))
                    .build()
            check(mic.getState() == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            mic.startRecording()
            check(mic.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone did not start"
            }
            startedAt = System.nanoTime()
            if (phone != null) phone!!.arm(startedAt)
            main.post(
                Runnable {
                    if (stopping.get() || !ownsConnection() || !BridgeState.connected) {
                        failure = "Session changed before signal"
                        stop()
                        return@Runnable
                    }
                    // This limit is only for this explicit diagnostic; ordinary app volume remains
                    // 0..100.
                    BridgeState.volume = volume
                    NativeSender.volume(volume)
                    BridgeState.publish()
                    armedAt = System.nanoTime() + 750000000L
                    armed = true
                    status.accept(
                        "ACOUSTIC_RECORDING volume=" + volume + " phoneSpeakerSignal=" + paired
                    )
                }
            )
            val block = ShortArray(480)
            val timestamp = AudioTimestamp()
            while (
                !stopping.get() &&
                    sampleCount < samples.capacity() &&
                    System.nanoTime() - startedAt < AcousticProbe.MAX_SECONDS * 1000000000L
            ) {
                val count =
                    mic.read(
                        block,
                        0,
                        min(block.size, samples.capacity() - sampleCount),
                        AudioRecord.READ_BLOCKING,
                    )
                val readAt = System.nanoTime()
                check(count > 0) { "Microphone read failed: " + count }
                if (!samples.append(block, count)) break
                sampleCount = samples.count()
                val result = mic.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
                if (
                    result == AudioRecord.SUCCESS &&
                        timestamp.nanoTime > 0 &&
                        timestamp.framePosition > 0
                )
                    anchors.add(
                        longArrayOf(
                            (sampleCount).toLong(),
                            timestamp.framePosition,
                            timestamp.nanoTime,
                            readAt,
                        )
                    )
            }
            if (anchors.size < 5) failure = "Reliable microphone timestamps unavailable"
        } catch (error: Exception) {
            failure = error.javaClass.getSimpleName() + ": " + error.message
        } finally {
            stopping.set(true)
            armed = false
            if (mic != null) {
                try {
                    mic.stop()
                } catch (ignored: IllegalStateException) {}
                mic.release()
            }
            if (phone != null) phone!!.close()
            AcousticProbe.current.compareAndSet(this, null)
            samples.finish()
            finished = true
            main.post(
                Runnable {
                    if (ownsConnection()) {
                        NativeSender.volume(0)
                        BridgeState.volume = 0
                        BridgeState.publish()
                    }
                    // Give the sender worker time to process the mute before orderly teardown.
                    main.postDelayed(
                        Runnable {
                            status.accept(
                                if (failure == null)
                                    ("ACOUSTIC_COMPLETE micFrames=" +
                                        sampleCount +
                                        " anchors=" +
                                        anchors.size)
                                else "ACOUSTIC_FAILED " + failure
                            )
                            finishedCallback.run()
                        },
                        (if (ownsConnection()) 200 else 0).toLong(),
                    )
                }
            )
        }
    }

    fun finished(): Boolean {
        return finished
    }

    private fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        armed = false
        AcousticProbe.current.compareAndSet(this, null)
        if (ownsConnection()) {
            NativeSender.volume(0)
            BridgeState.volume = 0
            BridgeState.publish()
        }
        if (recording == null) {
            finished = true
            main.post(finishedCallback)
        }
    }

    fun dump(writer: PrintWriter, includePcm: Boolean) {
        if (!finished) {
            writer.println("ACOUSTIC_PENDING")
            return
        }
        try {
            val captured = samples.snapshot()
            if (captured == null) {
                writer.println("ACOUSTIC_CLEARED")
                return
            }
            val result =
                JSONObject()
                    .put("schema", 1)
                    .put("microphoneRate", AcousticProbe.MIC_RATE)
                    .put("source", source)
                    .put("sampleCount", captured.size)
                    .put("startedAtNs", startedAt)
                    .put("toneFrames", AcousticSignal.TONE_FRAMES)
                    .put("outputRate", AcousticSignal.RATE)
                    .put("phoneSpeakerSignal", paired)
                    .put("signalMode", if (paired) "paired" else "homepod-only")
                    .put("reference", "sender PCM enqueue, not source capture")
                    .put("adcInternalDelayCalibrated", false)
                    .put("timebase", "MONOTONIC")
            if (failure != null) result.put("failure", failure)
            val stamps = JSONArray()
            val tones = JSONArray()
            for (anchor in anchors) stamps.put(JSONArray(anchor))
            synchronized(markers) {
                for (i in 0 until AcousticSignal.COUNT) tones.put(
                    JSONObject()
                        .put("destination", "homepod")
                        .put("enqueuedAtNs", markers[i])
                        .put(
                            "senderPipeline",
                            if (markerStages[i] == null) JSONObject.NULL
                            else JSONArray(markerStages[i]),
                        )
                        .put("phaseCode", AcousticSignal.PHASE_CODE!![i])
                        .put("lowHz", AcousticSignal.LOW!![i])
                        .put("highHz", AcousticSignal.HIGH!![i])
                )
            }
            if (phone != null) {
                val local = phone!!.metadata()
                result.put("phoneReference", local)
                val localTones = local!!.getJSONArray("tones")
                for (i in 0 until localTones.length()) tones.put(localTones.getJSONObject(i))
            }
            result.put("anchors", stamps).put("tones", tones)
            if (includePcm) {
                val pcm = ByteBuffer.allocate(captured.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in captured) pcm.putShort(sample)
                result.put(
                    "pcm16le",
                    Base64.encodeToString(pcm.array(), Base64.NO_WRAP),
                )
            }
            writer.println("ACOUSTIC_RECORD=" + result)
        } catch (error: Exception) {
            writer.println("ACOUSTIC_EXPORT_FAILED " + error.javaClass.getSimpleName())
        }
    }

    override fun close() {
        stop()
    }

    fun clear() {
        stop()
        samples.clear()
    }

    companion object {
        const val MAX_DIAGNOSTIC_VOLUME: Int = 35
        private val current: AtomicReference<AcousticProbe?> = AtomicReference<AcousticProbe?>()
        private const val MIC_RATE: Int = 48000
        private const val MAX_SECONDS: Int = 6

        @JvmStatic
        fun forLifetime(owner: IBinder?): AcousticProbe? {
            val probe = AcousticProbe.current.get()
            return if (
                probe != null && probe.lifetime === owner && probe.armed && !probe.stopping.get()
            )
                probe
            else null
        }
    }
}
