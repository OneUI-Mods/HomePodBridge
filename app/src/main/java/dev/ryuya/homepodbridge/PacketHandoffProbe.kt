package dev.ryuya.homepodbridge

import android.content.Context
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.ArrayList
import java.util.Collections
import java.util.function.LongSupplier
import kotlin.jvm.JvmStatic
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/* Measures fixed-zero capture-completion to ALAC readiness; never opens a receiver or network. */
object PacketHandoffProbe {
    @JvmStatic
    fun run(context: Context?): String? {
        if (BridgeState.capture != null || BridgeState.connected || BridgeState.volume != 0)
            return "PACKET_PROBE_BUSY"
        var owner: RootHalInput? = null
        var safeStartup: Boolean = true
        var codecOpened: Boolean = false
        val result = JSONObject()
        try {
            if (!(NativeSender.openZeroCodecProbe().also { codecOpened = it }))
                throw IOException("Codec probe unavailable")
            owner = RootHalInput(context!!, BridgeState.MUSIC, false, true, false)
            val pipe =
                PcmPipe(
                    Channels.newChannel(owner.input()),
                    true,
                    LongSupplier { System.nanoTime() },
                    PcmPipe.HAL_MAGIC,
                )
            val block = ByteBuffer.allocateDirect(176)
            val packet = ByteBuffer.allocateDirect(1408)
            val phases = JSONArray()
            for (packetFrames in intArrayOf(352, 88)) {
                packet.clear().limit(packetFrames * 4)
                val ages = ArrayList<Long>()
                var firstReadAt: Long = 0
                var encodedUs: Long = 0
                var encodedBytes: Long = 0
                val begin = System.nanoTime()
                while (System.nanoTime() - begin < 4000000000L) {
                    if (
                        Thread.currentThread().isInterrupted() ||
                            BridgeState.capture != null ||
                            BridgeState.connected ||
                            BridgeState.volume != 0
                    )
                        throw IOException("Sharing changed during zero-only probe")
                    val read = pipe.read(block)
                    if (read != BufferLimits.DEDICATED_FRAMES * 4)
                        throw IOException("Unexpected capture block")
                    for (i in 0 until read) if (block.get(i).toInt() != 0)
                        throw IOException("Nonzero probe PCM")
                    if (packet.position() == 0) {
                        val transit = pipe.transitMicros()
                        if (transit < 0) throw IOException("Missing capture completion timestamp")
                        firstReadAt = System.nanoTime() - transit * 1000
                    }
                    packet.put(block)
                    if (!packet.hasRemaining()) {
                        packet.flip()
                        val encoded = NativeSender.encodeZeroProbe(packet, packetFrames)
                        if (encoded == null || encoded.size != 2)
                            throw IOException("Zero PCM encoding failed")
                        val age: Long = (System.nanoTime() - firstReadAt) / 1000
                        if (System.nanoTime() - begin >= 250000000L) ages.add(age)
                        encodedUs += encoded[0]
                        encodedBytes += encoded[1]
                        packet.clear().limit(packetFrames * 4)
                    }
                }
                Collections.sort<Long?>(ages)
                phases.put(
                    JSONObject()
                        .put("packetFrames", packetFrames)
                        .put("readUnitFrames", BufferLimits.DEDICATED_FRAMES)
                        .put("observations", ages.size)
                        .put("encodedBytes", encodedBytes)
                        .put("totalEncodeUs", encodedUs)
                        .put("firstReadToEncodedMedianUs", PacketHandoffProbe.percentile(ages, 50))
                        .put("firstReadToEncodedP95Us", PacketHandoffProbe.percentile(ages, 95))
                        .put(
                            "firstReadToEncodedMaximumUs",
                            PacketHandoffProbe.percentile(ages, 100),
                        )
                )
            }
            result.put("phases", phases).put("zeroOnly", true).put("networkStarted", false)
        } catch (error: Exception) {
            if (error is RootHalInput.StartupException) safeStartup = error.safeFallback
            try {
                result.put("failure", error.javaClass.getSimpleName() + ": " + error.message)
            } catch (ignored: JSONException) {}
        } finally {
            if (codecOpened) NativeSender.closeZeroCodecProbe()
            var cleaned: Boolean = owner == null && safeStartup
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
        return if (sorted.isEmpty()) -1 else sorted.get((sorted.size - 1) * percent / 100)!!
    }
}
