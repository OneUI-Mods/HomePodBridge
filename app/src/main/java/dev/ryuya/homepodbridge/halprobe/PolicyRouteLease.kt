package dev.ryuya.homepodbridge.halprobe

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Process
import kotlin.jvm.JvmStatic

/* A connection for one currently registered self-UID virtual mix; never a physical device. */
class PolicyRouteLease private constructor(context: Context, address: String?) : AutoCloseable {
    private val system: Class<*>
    private val attributesType: Class<*>
    private val attributes: Any
    private val address: String
    private var attempted: Boolean = false

    init {
        if (
            Process.myUid() != 0 ||
                address == null ||
                !address.matches("[0-9]+:ap:[0-9]+mixp:[0-9]+".toRegex())
        )
            throw SecurityException("Owned root policy registration required")
        this.address = address
        var input: Boolean = false
        for (device in
            context
                .getSystemService<AudioManager?>(AudioManager::class.java)
                .getDevices(AudioManager.GET_DEVICES_INPUTS)) if (
            device.isSource() &&
                address == device.getAddress() &&
                device.getType() == AudioDeviceInfo.TYPE_REMOTE_SUBMIX
        )
            input = true
        if (!input) throw SecurityException("Owned policy input missing")
        system = Class.forName("android.media.AudioSystem")
        attributesType = Class.forName("android.media.AudioDeviceAttributes")
        // The native type is fixed AUDIO_DEVICE_OUT_REMOTE_SUBMIX, never supplied by a caller.
        attributes =
            attributesType
                .getConstructor(
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                )
                .newInstance(0x8000, address, "HomePod Bridge private capture")
        if (state() != 0) throw SecurityException("Virtual output already connected")
    }

    @Throws(Exception::class)
    private fun state(): Int {
        return system
            .getMethod("getDeviceConnectionState", Int::class.javaPrimitiveType, String::class.java)
            .invoke(null, 0x8000, address) as Int
    }

    @Throws(Exception::class)
    private fun change(value: Int): Int {
        return system
            .getMethod(
                "setDeviceConnectionState",
                attributesType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .invoke(null, attributes, value, 0) as Int
    }

    @Throws(Exception::class)
    override fun close() {
        if (!attempted) return
        check(!(state() != 0 && change(0) != 0)) { "Owned virtual output disconnect refused" }
        check(state() == 0) { "Virtual output still connected" }
        attempted = false
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun open(context: Context, address: String): PolicyRouteLease {
            val lease = PolicyRouteLease(context, address)
            try {
                lease.attempted = true
                check(!(lease.change(1) != 0 || lease.state() != 1)) {
                    "Owned virtual output connection refused"
                }
                return lease
            } catch (failure: Exception) {
                lease.close()
                throw failure
            }
        }
    }
}
