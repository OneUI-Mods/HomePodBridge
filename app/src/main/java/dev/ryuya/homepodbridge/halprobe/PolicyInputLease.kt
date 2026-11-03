package dev.ryuya.homepodbridge.halprobe

import android.content.Context
import android.media.AudioManager
import android.os.IBinder
import dev.ryuya.homepodbridge.compat.DexSymbols
import dev.ryuya.homepodbridge.compat.compatibleField
import dev.ryuya.homepodbridge.compat.compatibleMethod
import dev.ryuya.homepodbridge.compat.compatibleName
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
        api = DexSymbols.findClass("android.media.IAudioPolicyService")
        val binder: IBinder? =
            DexSymbols.findClass("android.os.ServiceManager")
                .compatibleMethod("getService", String::class.java)
                .invoke(null, "media.audio_policy") as IBinder?
        if (binder == null || api.compatibleName() != binder.getInterfaceDescriptor())
            throw SecurityException("Policy service unavailable")
        service =
            DexSymbols.findClass(api.compatibleName() + "\$Stub")
                .compatibleMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        val attrType = DexSymbols.findClass("android.media.audio.common.AudioAttributes")
        val attributes = attrType.getConstructor().newInstance()
        attrType.compatibleField("source").setInt(attributes, 8)
        attrType
            .compatibleField("tags")
            .set(attributes, arrayOf<String>("addr=" + address, "fixedVolume"))
        val configType = DexSymbols.findClass("android.media.audio.common.AudioConfigBase")
        val config = configType.getConstructor().newInstance()
        configType.compatibleField("sampleRate").setInt(config, 44100)
        val layout = DexSymbols.findClass("android.media.audio.common.AudioChannelLayout")
        configType
            .compatibleField("channelMask")
            .set(
                config,
                layout.compatibleMethod("layoutMask", Int::class.javaPrimitiveType).invoke(null, 3),
            )
        val formatType = DexSymbols.findClass("android.media.audio.common.AudioFormatDescription")
        val format = formatType.getConstructor().newInstance()
        formatType.compatibleField("type").setByte(format, 1.toByte())
        formatType.compatibleField("pcm").setByte(format, 1.toByte())
        formatType.compatibleField("encoding").set(format, "")
        configType.compatibleField("format").set(config, format)
        val attribution =
            context
                .getAttributionSource()
                .javaClass
                .compatibleMethod("asState")
                .invoke(context.getAttributionSource())
        val session =
            context
                .getSystemService<AudioManager?>(AudioManager::class.java)
                .generateAudioSessionId()
        check(session > 0) { "No audio session" }
        val response =
            api.compatibleMethod(
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
        port = response.javaClass.compatibleField("portId").getInt(response)
        input = response.javaClass.compatibleField("input").getInt(response)
        try {
            if (
                port <= 0 ||
                    input <= 0 ||
                    response.javaClass.compatibleField("source").getInt(response) != 8
            )
                throw SecurityException("Unexpected input source")
            val selected = response.javaClass.compatibleField("selectedDeviceId").getInt(response)
            var matched: Boolean = false
            for (device in
                context
                    .getSystemService<AudioManager?>(AudioManager::class.java)
                    .getDevices(AudioManager.GET_DEVICES_INPUTS)) if (
                device.getId() == selected && device.isSource() && address == device.getAddress()
            )
                matched = true
            if (!matched) throw SecurityException("Input did not select the owned virtual mix")
            api.compatibleMethod("startInput", Int::class.javaPrimitiveType).invoke(service, port)
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
                api.compatibleMethod("stopInput", Int::class.javaPrimitiveType)
                    .invoke(service, owned)
        } finally {
            api.compatibleMethod("releaseInput", Int::class.javaPrimitiveType)
                .invoke(service, owned)
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
