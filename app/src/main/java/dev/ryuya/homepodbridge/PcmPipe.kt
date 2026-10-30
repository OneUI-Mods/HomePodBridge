package dev.ryuya.homepodbridge

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.util.function.LongSupplier
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmOverloads
import kotlin.math.min

/* Reads framed stereo PCM despite arbitrary pipe read boundaries. */
class PcmPipe(input: ReadableByteChannel, timed: Boolean, clock: LongSupplier, magic: Int) {
    private val input: ReadableByteChannel
    private val timed: Boolean
    private val halZero: Boolean
    private val halMagic: Int
    private val header: ByteBuffer
    private val availableRecord: ByteBuffer?
    private val clock: LongSupplier
    private var transitMicros: Long = -1
    @Volatile private var pendingFrames: Long = -1

    @JvmOverloads
    constructor(
        input: ReadableByteChannel,
        timed: Boolean = false,
        clock: LongSupplier = LongSupplier { System.nanoTime() },
        halZero: Boolean = false,
    ) : this(input, timed, clock, if (halZero) PcmPipe.HAL_ZERO_MAGIC else 0)

    init {
        val halZero: Boolean = magic != 0
        require(!(magic != 0 && magic != PcmPipe.HAL_MAGIC && magic != PcmPipe.HAL_ZERO_MAGIC))
        require(!(halZero && !timed)) { "HAL records require timing" }
        this.input = input
        this.timed = timed
        this.clock = clock
        this.halZero = halZero
        this.halMagic = magic
        header = ByteBuffer.allocateDirect(if (halZero) 24 else 16)
        availableRecord = if (halZero) ByteBuffer.allocateDirect(200) else null
        if (availableRecord != null) availableRecord.limit(0)
    }

    fun pendingFrames(): Long {
        return pendingFrames
    }

    fun transitMicros(): Long {
        return transitMicros
    }

    @Throws(IOException::class)
    fun read(pcm: ByteBuffer): Int {
        pcm.clear()
        var capturedAt: Long = 0
        if (timed) {
            header.clear()
            if (fill(header) == 0) return -1
            if (header.position() != header.capacity()) throw IOException("Truncated timing header")
            header.flip()
            if (header.getInt() != (if (halZero) halMagic else PcmPipe.MAGIC))
                throw IOException("Invalid timing header")
            val length = header.getInt()
            capturedAt = header.getLong()
            if (halZero) {
                val pending = header.getLong()
                if (pending < (if (halMagic == PcmPipe.HAL_MAGIC) -1 else 0) || pending > 4096)
                    throw IOException("Invalid HAL queue depth")
                pendingFrames = pending
            }
            if (length <= 0 || length > pcm.capacity() || length % 4 != 0)
                throw IOException("Invalid PCM block length")
            if (
                halZero &&
                    ((length != 176 && (halMagic != PcmPipe.HAL_MAGIC || length != 88)) ||
                        capturedAt <= 0)
            )
                throw IOException("Invalid zero HAL record")
            pcm.limit(length)
        }
        val expected = pcm.remaining()
        val count = fill(pcm)
        if (timed && count != expected) throw IOException("Truncated timed PCM block")
        if (count % 4 != 0) throw IOException("Truncated stereo frame")
        if (timed) {
            val elapsed: Long = clock.getAsLong() - capturedAt
            transitMicros = if (capturedAt > 0 && elapsed >= 0) elapsed / 1000 else -1
        }
        pcm.flip()
        return if (count == 0) -1 else count
    }

    @Throws(IOException::class)
    private fun fill(buffer: ByteBuffer): Int {
        val start = buffer.position()
        while (buffer.hasRemaining()) {
            if (availableRecord != null) {
                if (!availableRecord.hasRemaining()) {
                    availableRecord.clear()
                    val read = input.read(availableRecord)
                    availableRecord.flip()
                    if (read < 0) break
                    if (read == 0) continue
                }
                val end = availableRecord.limit()
                availableRecord.limit(
                    availableRecord.position() +
                        min(buffer.remaining(), availableRecord.remaining())
                )
                buffer.put(availableRecord)
                availableRecord.limit(end)
                continue
            }
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
        }
        return buffer.position() - start
    }

    companion object {
        const val MAGIC: Int = 0x48425031
        const val HAL_ZERO_MAGIC: Int = 0x48425032
        const val HAL_MAGIC: Int = 0x48425033
    }
}
