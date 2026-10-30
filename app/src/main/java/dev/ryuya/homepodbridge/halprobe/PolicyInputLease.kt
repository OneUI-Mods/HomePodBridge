package dev.ryuya.homepodbridge.halprobe

import android.content.Context
import android.media.AudioManager
import android.os.IBinder
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/* Routing input claim without an AudioRecord/RecordTrack consumer. Always source8 + exact mix. */
class PolicyInputLease private constructor(context: Context, address: String?) : AutoCloseable {
    private val service: Any
    private val api: Class<*>
    private var port: Int
    private var started: Boolean = false
    @JvmField val input: Int

    init {
        if (address == null || !address.matches("[0-9]+:ap:[0-9]+mixp:[0-9]+".toRegex()))
            throw SecurityException("Unrecognized policy address")
        api = Class.forName("android.media.IAudioPolicyService")
        val binder: IBinder? =
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "media.audio_policy") as IBinder?
        if (binder == null || api.getName() != binder.getInterfaceDescriptor())
            throw SecurityException("Policy service unavailable")
        service =
            Class.forName(api.getName() + "\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        val attrType = Class.forName("android.media.audio.common.AudioAttributes")
        val attributes = attrType.getConstructor().newInstance()
        attrType.getField("source").setInt(attributes, 8)
        attrType.getField("tags").set(attributes, arrayOf<String>("addr=" + address, "fixedVolume"))
        val configType = Class.forName("android.media.audio.common.AudioConfigBase")
        val config = configType.getConstructor().newInstance()
        configType.getField("sampleRate").setInt(config, 44100)
        val layout = Class.forName("android.media.audio.common.AudioChannelLayout")
        configType
            .getField("channelMask")
            .set(
                config,
                layout.getMethod("layoutMask", Int::class.javaPrimitiveType).invoke(null, 3),
            )
        val formatType = Class.forName("android.media.audio.common.AudioFormatDescription")
        val format = formatType.getConstructor().newInstance()
        formatType.getField("type").setByte(format, 1.toByte())
        formatType.getField("pcm").setByte(format, 1.toByte())
        formatType.getField("encoding").set(format, "")
        configType.getField("format").set(config, format)
        val attribution =
            context
                .getAttributionSource()
                .javaClass
                .getMethod("asState")
                .invoke(context.getAttributionSource())
        val session =
            context
                .getSystemService<AudioManager?>(AudioManager::class.java)
                .generateAudioSessionId()
        check(session > 0) { "No audio session" }
        val response =
            api.getMethod(
                    "getInputForAttr",
                    attrType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    attribution.javaClass,
                    configType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(service, attributes, 0, -1, session, attribution, config, 0, 0)
        port = response.javaClass.getField("portId").getInt(response)
        input = response.javaClass.getField("input").getInt(response)
        try {
            if (
                port <= 0 ||
                    input <= 0 ||
                    response.javaClass.getField("source").getInt(response) != 8
            )
                throw SecurityException("Unexpected input source")
            val selected = response.javaClass.getField("selectedDeviceId").getInt(response)
            var matched: Boolean = false
            for (device in
                context
                    .getSystemService<AudioManager?>(AudioManager::class.java)
                    .getDevices(AudioManager.GET_DEVICES_INPUTS)) if (
                device.getId() == selected && device.isSource() && address == device.getAddress()
            )
                matched = true
            if (!matched) throw SecurityException("Input did not select the owned virtual mix")
            api.getMethod("startInput", Int::class.javaPrimitiveType).invoke(service, port)
            started = true
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    @Throws(Exception::class)
    override fun close() {
        if (port == 0) return
        val owned: Int = port
        port = 0
        try {
            if (started)
                api.getMethod("stopInput", Int::class.javaPrimitiveType).invoke(service, owned)
        } finally {
            api.getMethod("releaseInput", Int::class.javaPrimitiveType).invoke(service, owned)
            started = false
        }
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun open(context: Context, address: String): PolicyInputLease? {
            return PolicyInputLease(context, address)
        }
    }
}
