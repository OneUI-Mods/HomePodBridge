package dev.ryuya.homepodbridge.halprobe

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable.Creator
import java.util.HashSet
import kotlin.jvm.JvmStatic
import org.json.JSONArray
import org.json.JSONObject

/* Fixed, read-only V3 r_submix inventory; contains no mutating transactions. */
class ReadOnlyHalState {
    @kotlin.jvm.JvmField val module: IBinder

    init {
        val candidate =
            (Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .invoke(null, ReadOnlyHalState.DESCRIPTOR + "/r_submix") as IBinder?)
        if (candidate == null || ReadOnlyHalState.DESCRIPTOR != candidate.getInterfaceDescriptor())
            throw SecurityException()
        module = candidate
        var p = read(16777215)
        try {
            if (p!!.readInt() != 3) throw SecurityException("Unsupported version")
        } finally {
            p!!.recycle()
        }
        p = read(16777214)
        try {
            if ("eb5f1f5af402830af56b50eb66bdd60fd8cc774e" != p!!.readString())
                throw SecurityException("Unsupported hash")
        } finally {
            p!!.recycle()
        }
    }

    @Throws(Exception::class)
    fun read(transaction: Int): Parcel? {
        if (
            transaction != 8 &&
                transaction != 10 &&
                transaction != 11 &&
                transaction != 16777214 &&
                transaction != 16777215
        )
            throw SecurityException("Read-only method required")
        val input = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            input!!.writeInterfaceToken(ReadOnlyHalState.DESCRIPTOR)
            check(module.transact(transaction, input!!, reply, 0)) { "Unavailable" }
            reply!!.readException()
            return reply
        } catch (error: Exception) {
            reply!!.recycle()
            throw error
        } finally {
            input!!.recycle()
        }
    }

    @Throws(Exception::class)
    fun objects(transaction: Int, type: String?): Array<Any?>? {
        val reply = read(transaction)
        try {
            val count = reply!!.readInt()
            require(!(count < 0 || count > 128)) { "Invalid inventory length" }
            reply!!.setDataPosition(reply!!.dataPosition() - 4)
            val result =
                reply!!.createTypedArray<Any?>(
                    (Class.forName(type).getField("CREATOR").get(null) as Creator<Any?>?)!!
                )
            require(reply!!.dataAvail() == 0) { "Trailing inventory data" }
            return result
        } finally {
            reply!!.recycle()
        }
    }

    @Throws(Exception::class)
    fun snapshot(): JSONObject? {
        check(module.isBinderAlive()) { "Module instance died" }
        val ports = JSONArray()
        val configs = JSONArray()
        val patches = JSONArray()
        val seenPorts = HashSet<Int?>()
        val seenConfigs = HashSet<Int?>()
        for (port in objects(11, "android.media.audio.common.AudioPort")!!) {
            val id = ReadOnlyHalState.integer(port!!, "id")
            check(!(id <= 0 || !seenPorts.add(id))) { "Duplicate port" }
            val value = JSONObject().put("id", id)
            val ext = ReadOnlyHalState.field(port!!, "ext")
            if (ReadOnlyHalState.tag(ext!!, "device")) {
                val device =
                    ReadOnlyHalState.field(ReadOnlyHalState.call(ext!!, "getDevice")!!, "device")
                val type = ReadOnlyHalState.field(device!!, "type")
                val address = ReadOnlyHalState.field(device!!, "address")
                val kind = ReadOnlyHalState.integer(type!!, "type")
                val connection: String? = ReadOnlyHalState.field(type!!, "connection") as String?
                if ((kind != 143 && kind != 11) || "virtual" != connection)
                    throw SecurityException("Non-virtual device in submix module")
                value!!
                    .put("kind", "device")
                    .put("deviceType", kind)
                    .put(
                        "address",
                        if (ReadOnlyHalState.tag(address!!, "id"))
                            ReadOnlyHalState.call(address!!, "getId")
                        else JSONObject.NULL,
                    )
            } else if (ReadOnlyHalState.tag(ext!!, "mix")) {
                value!!
                    .put("kind", "mix")
                    .put(
                        "handle",
                        ReadOnlyHalState.integer(
                            ReadOnlyHalState.call(ext!!, "getMix")!!,
                            "handle",
                        ),
                    )
            } else throw IllegalStateException("Unknown port kind")
            ports.put(value)
        }
        for (config in objects(10, "android.media.audio.common.AudioPortConfig")!!) {
            val id = ReadOnlyHalState.integer(config!!, "id")
            check(!(id <= 0 || !seenConfigs.add(id))) { "Duplicate config" }
            val value =
                ReadOnlyHalState.format(config!!)
                    .put("id", id)
                    .put("portId", ReadOnlyHalState.integer(config!!, "portId"))
            val ext = ReadOnlyHalState.field(config!!, "ext")
            if (ReadOnlyHalState.tag(ext!!, "mix"))
                value!!.put(
                    "handle",
                    ReadOnlyHalState.integer(ReadOnlyHalState.call(ext!!, "getMix")!!, "handle"),
                )
            configs.put(value)
        }
        val reply = read(8)
        try {
            val seen = HashSet<Int?>()
            for (patch in FrozenHalParcel.patches(reply!!)) {
                check(!(patch!!.id <= 0 || !seen.add(patch!!.id))) { "Duplicate patch" }
                patches.put(
                    JSONObject()
                        .put("id", patch!!.id)
                        .put("sources", JSONArray(patch!!.sources))
                        .put("sinks", JSONArray(patch!!.sinks))
                        .put("minimumFrames", patch!!.minimumFrames)
                )
            }
            require(reply!!.dataAvail() == 0) { "Trailing patches" }
        } finally {
            reply!!.recycle()
        }
        return JSONObject()
            .put("version", 3)
            .put("ports", ports)
            .put("configs", configs)
            .put("patches", patches)
    }

    companion object {
        const val DESCRIPTOR: String = "android.hardware.audio.core.IModule"

        @kotlin.jvm.JvmStatic
        @Throws(Exception::class)
        fun field(`object`: Any, name: String?): Any? {
            return `object`.javaClass.getField(name).get(`object`)
        }

        @kotlin.jvm.JvmStatic
        @Throws(Exception::class)
        fun integer(`object`: Any, name: String?): Int {
            return (ReadOnlyHalState.field(`object`, name) as Number).toInt()
        }

        @kotlin.jvm.JvmStatic
        @Throws(Exception::class)
        fun call(`object`: Any, name: String?): Any? {
            return `object`.javaClass.getMethod(name).invoke(`object`)
        }

        @kotlin.jvm.JvmStatic
        @Throws(Exception::class)
        fun tag(`object`: Any, name: String?): Boolean {
            return ((ReadOnlyHalState.call(`object`, "getTag") as Number).toInt() ==
                `object`.javaClass.getField(name).getInt(null))
        }

        @kotlin.jvm.JvmStatic
        @Throws(Exception::class)
        fun format(`object`: Any): JSONObject {
            val result = JSONObject()
            val rate = ReadOnlyHalState.field(`object`, "sampleRate")
            val mask = ReadOnlyHalState.field(`object`, "channelMask")
            val format = ReadOnlyHalState.field(`object`, "format")
            result.put("rate", if (rate == null) -1 else ReadOnlyHalState.integer(rate, "value"))
            result.put(
                "stereo",
                mask != null &&
                    ReadOnlyHalState.tag(mask, "layoutMask") &&
                    (ReadOnlyHalState.call(mask, "getLayoutMask") as Number).toInt() == 3,
            )
            result.put(
                "pcm16",
                format != null &&
                    ReadOnlyHalState.integer(format, "type") == 1 &&
                    ReadOnlyHalState.integer(format, "pcm") == 1,
            )
            return result
        }

        @Throws(Exception::class)
        @JvmStatic
        fun main(args: Array<String>) {
            require(args.size == 0)
            println(ReadOnlyHalState().snapshot().toString())
        }
    }
}
