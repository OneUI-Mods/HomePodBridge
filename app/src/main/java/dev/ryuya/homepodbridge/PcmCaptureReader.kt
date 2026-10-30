package dev.ryuya.homepodbridge

import java.io.IOException
import java.nio.ByteBuffer
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.max

/* Drain available complete stereo frames; block for only one frame when the source is empty. */
class PcmCaptureReader(blockBytes: Int, drain: Boolean, reader: PcmCaptureReader.Reader) {
    fun interface Reader {
        fun read(buffer: ByteBuffer?, bytes: Int, mode: Int): Int
    }

    @JvmField val buffer: ByteBuffer
    private val afterFirstFrame: ByteBuffer
    private val reader: PcmCaptureReader.Reader
    private val drain: Boolean

    init {
        require(!(blockBytes < 4 || blockBytes % 4 != 0)) { "Stereo block required" }
        buffer = ByteBuffer.allocateDirect(blockBytes)
        buffer.position(4)
        afterFirstFrame = buffer.slice()
        buffer.clear()
        this.reader = reader
        this.drain = drain
    }

    @Throws(IOException::class)
    fun read(): Int {
        buffer.clear()
        var count = reader.read(buffer, buffer.capacity(), if (drain) 1 else 0)
        PcmCaptureReader.check(count, buffer.capacity())
        if (drain && count == 0) {
            count = reader.read(buffer, 4, 0)
            PcmCaptureReader.check(count, 4)
            if (count > 0 && afterFirstFrame.capacity() > 0) {
                val more = reader.read(afterFirstFrame, afterFirstFrame.capacity(), 1)
                PcmCaptureReader.check(more, afterFirstFrame.capacity())
                if (more < 0) return more
                count += more
            }
        }
        buffer.position(0).limit(max(0, count))
        return count
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        private fun check(count: Int, maximum: Int) {
            if (count > maximum || (count > 0 && count % 4 != 0))
                throw IOException("Invalid stereo capture read")
        }
    }
}
