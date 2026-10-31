package dev.ryuya.homepodbridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import dev.ryuya.homepodbridge.halprobe.PhoneTiming
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmOverloads
import kotlin.jvm.Synchronized

/* Explicit capture-only benchmark source: every byte is zero, never caller-provided audio. */
class SilentProbeSource @JvmOverloads constructor(periodFrames: Int = 928) : AutoCloseable {
    private val closed: AtomicBoolean = AtomicBoolean()
    private val periodFrames: Int

    private var track: AudioTrack? = null
    @Volatile private var writtenFrames: Long = 0
    @Volatile private var bufferFrames: Int = 0
    private var underruns: Int = 0

    init {
        var supported: Boolean = false
        for (ms in 1..21) if (PhoneTiming(ms, 96).periodFrames() == periodFrames) supported = true
        require(supported)
        this.periodFrames = periodFrames
    }

    @Synchronized
    fun underruns(): Int {
        return if (track == null) underruns else track!!.getUnderrunCount()
    }

    fun bufferFrames(): Int {
        return bufferFrames
    }

    fun writtenFrames(): Long {
        return writtenFrames
    }

    fun start() {
        Thread(Runnable { this.pump() }, "HomePod-zero-input").start()
    }

    private fun pump() {
        var created: AudioTrack? = null
        try {
            if (closed.get()) return
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            created =
                android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setSampleRate(44100)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(periodFrames * 4 * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            bufferFrames = created.getBufferSizeInFrames()
            synchronized(this) {
                if (closed.get()) return
                track = created
            }
            val zeros = ByteArray(periodFrames * 4)
            run {
                val primed = created.write(zeros, 0, zeros.size, AudioTrack.WRITE_NON_BLOCKING)
                if (primed > 0) writtenFrames += (primed / 4).toLong()
            }
            created.play()
            while (!closed.get()) {
                var offset: Int = 0
                while (offset < zeros.size && !closed.get()) {
                    val count =
                        created.write(zeros, offset, zeros.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (count <= 0) return
                    writtenFrames += (count / 4).toLong()
                    offset += count
                }
            }
        } catch (ignored: RuntimeException) {
            // An unsupported source makes diagnostics inconclusive; normal capture is unaffected.
        } finally {
            synchronized(this) {
                if (created != null) underruns = created.getUnderrunCount()
                track = null
            }
            if (created != null) {
                try {
                    created.stop()
                } catch (ignored: RuntimeException) {}
                created.release()
            }
        }
    }

    override fun close() {
        closed.set(true)
        synchronized(this) {
            if (track != null) {
                try {
                    track!!.stop()
                } catch (ignored: RuntimeException) {}
            }
        }
    }
}
