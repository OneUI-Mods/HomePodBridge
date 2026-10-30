package dev.ryuya.homepodbridge.halprobe

import java.util.HashSet
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Fail-closed ownership decision for the closed root-self-UID getter diagnostic. */
object PeriodRouteGuard {
    @JvmStatic
    fun allows(
        ports: MutableList<PeriodRouteGuard.Port?>,
        configs: MutableList<PeriodRouteGuard.Config?>,
        address: String?,
        id: Int,
        port: Int,
    ): Boolean {
        if (
            address == null ||
                !address.matches("[0-9]+:ap:[0-9]+mixp:[0-9]+".toRegex()) ||
                id <= 0 ||
                (port != 3 && port != 4)
        )
            return false
        var matching: Int = 0
        var activeMixes: Int = 0
        var deviceConfigs: Int = 0
        val ids = HashSet<Int?>()
        for (c in configs) {
            if (c!!.id <= 0 || !ids.add(c!!.id)) return false
            if (c!!.port === port) activeMixes++
            if (
                c!!.id === id &&
                    c!!.port === port &&
                    c!!.rate === 44100 &&
                    c!!.stereo &&
                    c!!.pcm16 &&
                    c!!.handle > 0
            )
                matching++
            for (p in ports) {
                if (p!!.id !== c!!.port || (p!!.type !== 143 && p!!.type !== 11)) continue
                if (address != p!!.address) return false
                if (p!!.type === (if (port == 3) 143 else 11)) deviceConfigs++
            }
        }
        return matching == 1 && activeMixes == 1 && deviceConfigs == 1
    }

    @JvmRecord
    data class Port(val id: Int, val type: Int, val address: String?) {
        override fun toString(): String = "Port[id=$id, type=$type, address=$address]"
    }

    @JvmRecord
    data class Config(
        val id: Int,
        val port: Int,
        val rate: Int,
        val stereo: Boolean,
        val pcm16: Boolean,
        val handle: Int,
    ) {
        override fun toString(): String =
            "Config[id=$id, port=$port, rate=$rate, stereo=$stereo, pcm16=$pcm16, handle=$handle]"
    }
}
