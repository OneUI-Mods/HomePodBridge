package dev.ryuya.homepodbridge.halprobe

import dev.ryuya.homepodbridge.SilentProbeSource
import java.util.ArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmOverloads
import kotlin.jvm.Synchronized
import kotlin.math.max

/* Diagnostic-only source, optionally stopping and recreating fixed-zero playback. */
class ZeroPlayback @JvmOverloads constructor(pulse: Boolean, periodFrames: Int = 928) :
    AutoCloseable {
    private val sources: MutableList<SilentProbeSource?> = ArrayList<SilentProbeSource?>()
    private val timer: ScheduledExecutorService? = Executors.newSingleThreadScheduledExecutor()
    private val periodFrames: Int
    private var current: SilentProbeSource? = null
    private var closed: Boolean = false

    init {
        this.periodFrames = periodFrames
        toggle()
        if (pulse) timer!!.scheduleAtFixedRate(Runnable { this.toggle() }, 3, 3, TimeUnit.SECONDS)
    }

    @Synchronized
    private fun toggle() {
        if (closed) return
        if (current != null) {
            current!!.close()
            current = null
        } else {
            current = SilentProbeSource(periodFrames)
            sources.add(current)
            current!!.start()
        }
    }

    @Synchronized
    fun underruns(): Int {
        return sources.stream().mapToInt { obj: SilentProbeSource? -> obj!!.underruns() }.sum()
    }

    @Synchronized
    fun bufferFrames(): Int {
        return sources
            .stream()
            .mapToInt { obj: SilentProbeSource? -> obj!!.bufferFrames() }
            .max()
            .orElse(0)
    }

    @Synchronized
    fun writtenFrames(): Long {
        return sources.stream().mapToLong { obj: SilentProbeSource? -> obj!!.writtenFrames() }.sum()
    }

    @Synchronized
    override fun close() {
        closed = true
        timer!!.shutdownNow()
        if (current != null) current!!.close()
        current = null
    }
}
