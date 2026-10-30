package dev.ryuya.homepodbridge

import android.content.Context
import dev.ryuya.homepodbridge.halprobe.PhoneTiming
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.ArrayList
import java.util.Collections
import java.util.function.LongSupplier
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min
import org.json.JSONException
import org.json.JSONObject

/*
 * Bounded self-UID, fixed-zero capture through the actual app pipe; no receiver or network sender.
 */
object LocalHalProbe {
    @JvmStatic
    @JvmOverloads
    fun run(
        context: Context?,
        target: Int = 800,
        pulse: Boolean = true,
        shortPeriod: Boolean = false,
    ): String? {
        return LocalHalProbe.run(
            context,
            target,
            pulse,
            shortPeriod,
            PhoneTiming.fastest()!!,
        )
    }

    @JvmStatic
    fun runTiming(context: Context?, timing: PhoneTiming): String? {
        return LocalHalProbe.run(context, 800, false, true, timing)
    }

    @JvmStatic
    private fun run(
        context: Context?,
        target: Int,
        pulse: Boolean,
        shortPeriod: Boolean,
        timing: PhoneTiming,
    ): String? {
        if (BridgeState.capture != null || BridgeState.connected || BridgeState.volume != 0)
            return "LOCAL_HAL_BUSY"
        val result = JSONObject()
        var owner: RootHalInput? = null
        var startupCleanupSafe: Boolean = true
        try {
            owner =
                if (shortPeriod)
                    RootHalInput(context!!, BridgeState.MUSIC, false, true, pulse, timing)
                else RootHalInput(context!!, BridgeState.MUSIC, false, true, pulse, target)
            val pipe =
                PcmPipe(
                    Channels.newChannel(owner.input()),
                    true,
                    LongSupplier { System.nanoTime() },
                    PcmPipe.HAL_MAGIC,
                )
            val pcm = ByteBuffer.allocateDirect(176)
            val transits = ArrayList<Long>()
            var frames: Long = 0
            var blocks: Long = 0
            var pendingSum: Long = 0
            var active: Long = 0
            var maximumPending: Long = 0
            var idleBlocks: Long = 0
            val begin = System.nanoTime()
            var firstActiveAt: Long = -1
            var steadyIdle: Long = 0
            while (System.nanoTime() - begin < 10000000000L) {
                if (
                    Thread.currentThread().isInterrupted() ||
                        BridgeState.capture != null ||
                        BridgeState.connected ||
                        BridgeState.volume != 0
                )
                    throw IOException("Sharing changed during zero-only test")
                val read = pipe.read(pcm)
                if (read != BufferLimits.DEDICATED_FRAMES * 4) throw IOException("Unexpected block")
                for (i in 0 until read) if (pcm.get(i).toInt() != 0)
                    throw IOException("Nonzero diagnostic PCM")
                frames += (read / 4).toLong()
                blocks++
                val transit = pipe.transitMicros()
                if (transit >= 0) transits.add(transit)
                val pending = pipe.pendingFrames()
                if (pending < 0) {
                    idleBlocks++
                    if (System.nanoTime() - begin >= 500000000L) steadyIdle++
                } else {
                    if (firstActiveAt < 0) firstActiveAt = (System.nanoTime() - begin) / 1000
                    if (System.nanoTime() - begin >= 500000000L) {
                        pendingSum += pending
                        active++
                        maximumPending = max(maximumPending, pending)
                    }
                }
            }
            val elapsedUs: Long = (System.nanoTime() - begin) / 1000
            Collections.sort<Long?>(transits)
            val realtime: Boolean =
                frames * 1000000L >= elapsedUs * 44100L * 95 / 100 &&
                    frames * 1000000L <= elapsedUs * 44100L * 105 / 100
            result
                .put("targetFillFrames", if (shortPeriod) timing.reserveFrames else target)
                .put("shortMixerPeriod", shortPeriod)
                .put("requestedPeriodMs", if (shortPeriod) timing.periodMs else 21)
                .put("producerPeriodFrames", if (shortPeriod) timing.periodFrames() else 928)
                .put("nonce", owner.nonce)
                .put("frames", frames)
                .put("blocks", blocks)
                .put("elapsedUs", elapsedUs)
                .put("realtime", realtime)
                .put("framesPerRead", BufferLimits.DEDICATED_FRAMES)
                .put("zeroOnly", true)
                .put("networkStarted", false)
                .put("idleBlocks", idleBlocks)
                .put("steadyIdleBlocks", steadyIdle)
                .put("firstActiveUs", firstActiveAt)
                .put("steadyMeanPendingFrames", if (active == 0L) -1 else pendingSum / active)
                .put("steadyMaximumPendingFrames", maximumPending)
                .put("medianTransitUs", LocalHalProbe.percentile(transits, 50))
                .put("p95TransitUs", LocalHalProbe.percentile(transits, 95))
                .put("maximumTransitUs", LocalHalProbe.percentile(transits, 100))
        } catch (error: Exception) {
            if (error is RootHalInput.StartupException) startupCleanupSafe = error.safeFallback
            try {
                result.put("failure", error.javaClass.getSimpleName() + ": " + error.message)
            } catch (ignored: JSONException) {}
        } finally {
            var cleaned: Boolean = owner == null && startupCleanupSafe
            if (owner != null) {
                val interrupted = Thread.interrupted()
                owner.close()
                cleaned = owner.awaitCleanup(5000)
                if (interrupted) Thread.currentThread().interrupt()
            }
            if (!cleaned) CaptureService.blockUnconfirmedHalCleanup()
            try {
                result.put("cleanupConfirmed", cleaned)
            } catch (ignored: JSONException) {}
        }
        return result.toString()
    }

    @JvmStatic
    private fun percentile(sorted: ArrayList<Long>, percent: Int): Long {
        return if (sorted.isEmpty()) -1
        else sorted.get(min(sorted.size - 1, (sorted.size - 1) * percent / 100))!!
    }
}
