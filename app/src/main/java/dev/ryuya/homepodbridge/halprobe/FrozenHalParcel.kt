package dev.ryuya.homepodbridge.halprobe

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import java.util.ArrayList
import java.util.function.Consumer
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/* Small, bounded readers for the verified V3 module. No HAL transactions or setters. */
object FrozenHalParcel {
    @JvmStatic
    fun bad(): IllegalArgumentException {
        return IllegalArgumentException("Invalid frozen parcel")
    }

    @JvmStatic
    fun typed(p: Parcel): FrozenHalParcel.Span? {
        if (p.dataAvail() < 4 || p.readInt() != 1) throw FrozenHalParcel.bad()!!
        return FrozenHalParcel.Span(p, p.dataSize())
    }

    @JvmStatic
    fun patch(p: Parcel): FrozenHalParcel.Patch {
        FrozenHalParcel.typed(p).use { b ->
            val value = FrozenHalParcel.Patch()
            value.id = b!!.integer()
            value.sources = b!!.integers(16)
            value.sinks = b!!.integers(16)
            value.minimumFrames = b!!.integer()
            value.latencies = b!!.integers(16)
            return value
        }
    }

    @JvmStatic
    fun patches(p: Parcel): MutableList<FrozenHalParcel.Patch?> {
        if (p.dataAvail() < 4) throw FrozenHalParcel.bad()!!
        val count = p.readInt()
        if (count < 0 || count > 128) throw FrozenHalParcel.bad()!!
        val values = ArrayList<FrozenHalParcel.Patch?>()
        for (i in 0 until count) values.add(FrozenHalParcel.patch(p))
        return values
    }

    @JvmStatic
    fun writePatch(p: Parcel, id: Int, sources: IntArray, sinks: IntArray) {
        if (id != 0 || sources.size < 1 || sources.size > 16 || sinks.size < 1 || sinks.size > 16)
            throw FrozenHalParcel.bad()!!
        p.writeInt(1)
        val begin = p.dataPosition()
        p.writeInt(0)
        p.writeInt(id)
        p.writeIntArray(sources)
        p.writeIntArray(sinks)
        p.writeInt(0)
        p.writeIntArray(IntArray(0))
        val end = p.dataPosition()
        p.setDataPosition(begin)
        p.writeInt(end - begin)
        p.setDataPosition(end)
    }

    @JvmStatic
    fun begin(p: Parcel): Int {
        val start = p.dataPosition()
        p.writeInt(0)
        return start
    }

    @JvmStatic
    fun end(p: Parcel, start: Int) {
        val stop = p.dataPosition()
        p.setDataPosition(start)
        p.writeInt(stop - start)
        p.setDataPosition(stop)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun writeOpenArgs(p: Parcel, config: Int, frames: Long, input: Boolean) {
        if (config <= 0 || frames < 1 || frames > 262144) throw FrozenHalParcel.bad()!!
        val mask =
            Class.forName("android.media.audio.common.AudioChannelLayout")
                .getMethod("layoutMask", Int::class.javaPrimitiveType)
                .invoke(null, 3)
        p.writeInt(1)
        val args = FrozenHalParcel.begin(p)
        p.writeInt(config)
        p.writeInt(1)
        val metadata = FrozenHalParcel.begin(p)
        p.writeInt(1) // One track, never a microphone source.
        p.writeInt(1)
        val track = FrozenHalParcel.begin(p)
        if (input) {
            p.writeInt(8)
            p.writeFloat(1f)
            p.writeInt(0)
        } // REMOTE_SUBMIX, null destination.
        else {
            p.writeInt(1)
            p.writeInt(2)
            p.writeFloat(1f)
        } // MEDIA/MUSIC.

        p.writeTypedObject<Parcelable?>(mask as Parcelable?, 0)
        if (!input) p.writeInt(0) // Null source device.

        p.writeStringArray(arrayOfNulls<String>(0))
        FrozenHalParcel.end(p, track)
        FrozenHalParcel.end(p, metadata)
        if (!input) p.writeInt(0) // No offload.

        p.writeLong(frames)
        if (!input) {
            p.writeStrongBinder(null)
            p.writeStrongBinder(null)
        }
        FrozenHalParcel.end(p, args)
    }

    @JvmStatic
    @JvmOverloads
    fun stream(
        p: Parcel,
        opened: Consumer<IBinder?> = Consumer { binder: IBinder? -> },
    ): FrozenHalParcel.Stream {
        val result = FrozenHalParcel.Stream()
        try {
            FrozenHalParcel.typed(p).use { b ->
                b!!.require(4)
                result.binder = p.readStrongBinder()
                b!!.require(0)
                if (result.binder == null) throw FrozenHalParcel.bad()!!
                opened.accept(
                    result.binder
                ) // Supervisor retains the endpoint before decoding FD payloads.
                b!!.typed().use { desc ->
                    result.command = FrozenHalParcel.queue(desc!!, result, 8)
                    result.reply = FrozenHalParcel.queue(desc!!, result, 56)
                    result.frameBytes = desc!!.integer()
                    result.bufferFrames = desc!!.number()
                    if (
                        result.frameBytes != 4 ||
                            result.bufferFrames < 1 ||
                            result.bufferFrames > 262144
                    )
                        throw FrozenHalParcel.bad()!!
                    if (desc!!.integer() != 1 || desc!!.integer() != 0)
                        throw FrozenHalParcel.bad()!! // Required FMQ union, never mmap.

                    result.data = FrozenHalParcel.queue(desc!!, result, 1)
                    if (
                        result!!.data!!.grants!![2]!!.extent !=
                            result.frameBytes * result.bufferFrames
                    )
                        throw FrozenHalParcel.bad()!!
                }
                return result
            }
        } catch (failure: Throwable) {
            result.close()
            throw failure
        }
    }

    @JvmStatic
    private fun queue(
        parent: FrozenHalParcel.Span,
        owner: FrozenHalParcel.Stream,
        quantum: Int,
    ): FrozenHalParcel.Queue {
        val p = parent.parcel
        val q = FrozenHalParcel.Queue()
        parent.typed().use { b ->
            val count = b!!.integer()
            if (count != 3 && count != 4) throw FrozenHalParcel.bad()!!
            q.grants = arrayOfNulls<FrozenHalParcel.Grant>(count)
            for (i in 0 until count) b!!.typed().use { grant ->
                val fd = grant!!.integer()
                val offset = grant!!.integer()
                val extent = grant!!.number()
                if (fd < 0 || fd >= 8 || offset < 0 || extent < 1 || extent > 4 * 1024 * 1024)
                    throw FrozenHalParcel.bad()!!
                q!!.grants!![i] = FrozenHalParcel.Grant(fd, offset, extent)
            }
            b!!.typed().use { h ->
                val fds = h!!.integer()
                if (fds < 1 || fds > 8) throw FrozenHalParcel.bad()!!
                q.fds = arrayOfNulls<ParcelFileDescriptor>(fds)
                for (i in 0 until fds) {
                    h!!.require(4)
                    val fd = p.readTypedObject<ParcelFileDescriptor?>(ParcelFileDescriptor.CREATOR)
                    if (fd != null) owner.acquired.add(fd)
                    h!!.require(0)
                    if (fd == null) throw FrozenHalParcel.bad()!!
                    q!!.fds!![i] = fd
                }
                if (h!!.integers(16).size != 0) throw FrozenHalParcel.bad()!!
            }
            q.quantum = b!!.integer()
            q.flags = b!!.integer()
            if (q.quantum != quantum || q.flags != 1) throw FrozenHalParcel.bad()!!
            for (i in 0 until count) {
                val g: FrozenHalParcel.Grant? = q!!.grants!![i]
                if (g!!.fd >= q.fds!!.size) throw FrozenHalParcel.bad()!!
                if (i < 2 && (g!!.extent != 8L || g!!.offset % 8 != 0))
                    throw FrozenHalParcel.bad()!!
                if (i == 3 && (g!!.extent != 4L || g!!.offset % 4 != 0))
                    throw FrozenHalParcel.bad()!!
            }
            if (q!!.grants!![2]!!.extent % quantum != 0L) throw FrozenHalParcel.bad()!!
            return q
        }
    }

    class Patch {
        @JvmField var id: Int = 0
        @JvmField var minimumFrames: Int = 0
        @JvmField var sources: IntArray? = null
        @JvmField var sinks: IntArray? = null
        @JvmField var latencies: IntArray? = null
    }

    class Grant(fd: Int, offset: Int, extent: Long) {
        @JvmField val fd: Int
        @JvmField val offset: Int
        @JvmField val extent: Long

        init {
            this.fd = fd
            this.offset = offset
            this.extent = extent
        }
    }

    class Queue {
        @JvmField var grants: Array<FrozenHalParcel.Grant?>? = null
        @JvmField var fds: Array<ParcelFileDescriptor?>? = null
        @JvmField var quantum: Int = 0
        @JvmField var flags: Int = 0
    }

    class Stream : AutoCloseable {
        @JvmField var binder: IBinder? = null
        @JvmField var command: FrozenHalParcel.Queue? = null
        @JvmField var reply: FrozenHalParcel.Queue? = null
        @JvmField var data: FrozenHalParcel.Queue? = null
        @JvmField var frameBytes: Int = 0
        @JvmField var bufferFrames: Long = 0
        @JvmField
        val acquired: MutableList<ParcelFileDescriptor?> = ArrayList<ParcelFileDescriptor?>()

        override fun close() {
            for (fd in acquired) try {
                fd!!.close()
            } catch (ignored: Exception) {}
            acquired.clear()
        }
    }

    class Span(p: Parcel, parent: Int) : AutoCloseable {
        @JvmField val parcel: Parcel
        @JvmField val end: Int

        init {
            parcel = p
            val begin = p.dataPosition()
            if (begin < 0 || begin > parent - 4 || parent > p.dataSize())
                throw FrozenHalParcel.bad()!!
            val size = p.readInt()
            if (size < 4 || size > 65536 || size > parent - begin) throw FrozenHalParcel.bad()!!
            end = begin + size
        }

        fun require(size: Int) {
            if (size < 0 || parcel.dataPosition() > end - size) throw FrozenHalParcel.bad()!!
        }

        fun integer(): Int {
            require(4)
            return parcel.readInt()
        }

        fun number(): Long {
            require(8)
            return parcel.readLong()
        }

        fun integers(max: Int): IntArray {
            val count = integer()
            if (count < 0 || count > max) throw FrozenHalParcel.bad()!!
            require(count * 4)
            val result = IntArray(count)
            for (i in 0 until count) result[i] = parcel.readInt()
            return result
        }

        fun typed(): FrozenHalParcel.Span? {
            if (integer() != 1) throw FrozenHalParcel.bad()!!
            return FrozenHalParcel.Span(parcel, end)
        }

        override fun close() {
            parcel.setDataPosition(end)
        }
    }
}
