package dev.ryuya.homepodbridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import java.util.ArrayList
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import org.json.JSONArray
import org.json.JSONObject

/*
 * Explicit local reference only. Fixed signals, verified built-in speaker, restored media volume.
 */
class PhoneAcousticReference(context: Context) : AutoCloseable {
    private val context: Context
    private val closed: AtomicBoolean = AtomicBoolean()
    private val ready: CountDownLatch = CountDownLatch(1)
    private val timestamps: ArrayList<LongArray?> = ArrayList<LongArray?>()
    private val markers: LongArray = LongArray(PhoneAcousticReference.COUNT)
    private val markerFrames: LongArray = LongArray(PhoneAcousticReference.COUNT)
    private val worker: Thread
    @Volatile private var armAt: Long = 0
    @Volatile private var failure: String? = null
    private var oldVolume: Int = -1
    private var volume: Int = -1
    private var maxVolume: Int = 0
    private var deviceType: Int = 0
    private var bufferFrames: Int = 0
    private var performance: Int = 0
    private var underruns: Int = 0
    private var volumeRestored: Boolean = false

    init {
        this.context = context
        worker = Thread(Runnable { this.pump() }, "HomePod-phone-reference")
    }

    @Throws(Exception::class)
    fun start() {
        worker.start()
        if (
            !ready.await(3, TimeUnit.SECONDS) ||
                failure != null ||
                deviceType != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        ) {
            close()
            throw IllegalStateException("Phone speaker reference unavailable: " + failure)
        }
    }

    fun arm(microphoneStart: Long) {
        armAt = microphoneStart
    }

    private fun pump() {
        var track: AudioTrack? = null
        val manager = context.getSystemService<AudioManager?>(AudioManager::class.java)
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            oldVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            check(!(maxVolume < 4 || oldVolume > maxVolume / 4)) {
                "Phone media volume exceeds diagnostic ceiling"
            }
            if (oldVolume == 0) manager.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
            volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            check(!(volume < 1 || volume > maxVolume / 4)) { "Phone diagnostic volume not bounded" }
            var speaker: AudioDeviceInfo? = null
            for (device in manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if (
                device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            ) {
                speaker = device
                break
            }
            checkNotNull(speaker) { "Built-in speaker missing" }
            val minimum =
                AudioTrack.getMinBufferSize(
                    PhoneAcousticReference.RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            check(minimum > 0) { "Phone output format unsupported" }
            track =
                android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setSampleRate(PhoneAcousticReference.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(max(minimum, 480 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            check(
                !(track.getState() != AudioTrack.STATE_INITIALIZED ||
                    !track.setPreferredDevice(speaker))
            ) {
                "Phone speaker route refused"
            }
            track.setBufferSizeInFrames(480)
            bufferFrames = track.getBufferSizeInFrames()
            performance = track.getPerformanceMode()
            track.setStartThresholdInFrames(min(bufferFrames, 240))
            val block = ShortArray(480)
            var written: Long = 0
            val prime = track.write(block, 0, block.size, AudioTrack.WRITE_NON_BLOCKING)
            if (prime > 0) written = (prime / 2).toLong()
            track.play()
            val timestamp = AudioTimestamp()
            var next: Int = 0
            var active: Int = -1
            var toneFrame: Int = 0
            while (!closed.get()) {
                val routed = track.getRoutedDevice()
                check(!(routed == null && deviceType != 0)) { "Phone speaker route became unknown" }
                if (routed != null) {
                    deviceType = routed.getType()
                    check(deviceType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        "Phone reference route changed"
                    }
                    ready.countDown()
                }
                Arrays.fill(block, 0.toShort())
                val now = System.nanoTime()
                var pending: Int = -1
                var pendingFrame: Int = 0
                if (
                    deviceType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
                        armAt > 0 &&
                        next < PhoneAcousticReference.COUNT &&
                        now >= armAt + 900000000L + next * 600000000L
                ) {
                    active = next++
                    toneFrame = 0
                    pending = active
                    pendingFrame = 0
                }
                for (f in 0..239) if (active >= 0) {
                    val source: Double =
                        toneFrame * AcousticSignal.RATE.toDouble() / PhoneAcousticReference.RATE
                    val left = source.toInt()
                    if (left >= AcousticSignal.TONE_FRAMES) {
                        active = -1
                        continue
                    }
                    val fraction: Double = source - left
                    val a =
                        AcousticSignal.sample(
                            PhoneAcousticReference.LOW!![active],
                            PhoneAcousticReference.HIGH!![active],
                            PhoneAcousticReference.CODE!![active],
                            left,
                        )
                    val b =
                        AcousticSignal.sample(
                            PhoneAcousticReference.LOW!![active],
                            PhoneAcousticReference.HIGH!![active],
                            PhoneAcousticReference.CODE!![active],
                            left + 1,
                        )
                    val sample = Math.round(a + (b - a) * fraction).toShort()
                    block[f * 2] = sample
                    block[f * 2 + 1] = sample
                    toneFrame++
                }
                var offset: Int = 0
                while (offset < block.size && !closed.get()) {
                    val count =
                        track.write(block, offset, block.size - offset, AudioTrack.WRITE_BLOCKING)
                    val acceptedAt = System.nanoTime()
                    check(!(count <= 0 || count % 2 != 0)) { "Phone reference write failed" }
                    if (
                        pending >= 0 &&
                            pendingFrame * 2 >= offset &&
                            pendingFrame * 2 < offset + count
                    ) {
                        markers[pending] =
                            acceptedAt +
                                (pendingFrame - offset / 2) * 1000000000L /
                                    PhoneAcousticReference.RATE
                        markerFrames[pending] = written + pendingFrame - offset / 2
                        pending = -1
                    }
                    written += (count / 2).toLong()
                    offset += count
                }
                if (
                    track.getTimestamp(timestamp) &&
                        timestamp.nanoTime > 0 &&
                        timestamp.framePosition > 0
                )
                    timestamps.add(
                        longArrayOf(
                            timestamp.framePosition,
                            timestamp.nanoTime,
                            written,
                            System.nanoTime(),
                        )
                    )
            }
        } catch (error: Exception) {
            failure = error.javaClass.getSimpleName() + ": " + error.message
        } finally {
            if (track != null) {
                underruns = track.getUnderrunCount()
                try {
                    track.stop()
                } catch (ignored: IllegalStateException) {}
                track.release()
            }
            if (oldVolume >= 0 && oldVolume <= maxVolume / 4) {
                try {
                    val currentVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (currentVolume == volume)
                        manager.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0)
                    volumeRestored = manager.getStreamVolume(AudioManager.STREAM_MUSIC) == oldVolume
                } catch (ignored: RuntimeException) {}
            }
            ready.countDown()
        }
    }

    @Throws(Exception::class)
    fun metadata(): JSONObject? {
        val result =
            JSONObject()
                .put("rate", PhoneAcousticReference.RATE)
                .put("routedDeviceType", deviceType)
                .put("bufferFrames", bufferFrames)
                .put("performanceMode", performance)
                .put("underruns", underruns)
                .put("volumeBefore", oldVolume)
                .put("volumeDuring", volume)
                .put("volumeMax", maxVolume)
                .put("volumeRestored", volumeRestored)
        if (failure != null) result.put("failure", failure)
        if (worker.isAlive())
            return result
                .put("failure", "Phone reference is still active")
                .put("anchors", JSONArray())
                .put("tones", JSONArray())
        val anchors = JSONArray()
        val tones = JSONArray()
        for (stamp in timestamps) anchors.put(JSONArray(stamp))
        for (i in 0 until PhoneAcousticReference.COUNT) tones.put(
            JSONObject()
                .put("destination", "phone")
                .put("enqueuedAtNs", markers[i])
                .put("trackFrame", markerFrames[i])
                .put("lowHz", PhoneAcousticReference.LOW!![i])
                .put("highHz", PhoneAcousticReference.HIGH!![i])
                .put("phaseCode", PhoneAcousticReference.CODE!![i])
        )
        return result.put("anchors", anchors).put("tones", tones)
    }

    override fun close() {
        closed.set(true)
        try {
            worker.join(1500)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (worker.isAlive()) failure = "Phone reference cleanup unconfirmed"
    }

    companion object {
        const val RATE: Int = 48000
        const val COUNT: Int = 2
        @JvmField val LOW: IntArray? = intArrayOf(1800, 4300)
        @JvmField val HIGH: IntArray? = intArrayOf(4600, 1500)
        @JvmField val CODE: IntArray? = intArrayOf(0x17D4, 0xC2B9)
    }
}
