package dev.ryuya.homepodbridge

import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

@JvmRecord
data class Receiver(
    val id: String?,
    val name: String?,
    val host: String?,
    val port: Int,
    val deviceId: String?,
) {

    companion object {
        @JvmStatic
        fun from(
            serviceName: String?,
            address: InetAddress,
            port: Int,
            txt: MutableMap<String?, ByteArray?>,
        ): Receiver? {
            if ((address !is Inet4Address) || port <= 0 || port > 65535) return null
            val model = Receiver.value(txt, "am")
            if (!model!!.startsWith("AudioAccessory") && !model!!.lowercase().contains("homepod"))
                return null
            if ("true".equals(Receiver.value(txt, "pw"), ignoreCase = true)) return null
            var name: String? = serviceName
            var deviceId = Receiver.value(txt, "deviceid")
            val at = name!!.indexOf('@')
            if (at > 0) {
                if (deviceId!!.isEmpty()) deviceId = name!!.substring(0, at)
                name = name!!.substring(at + 1)
            }
            if (deviceId!!.isEmpty()) return null
            val key = deviceId!!.uppercase().replace(":", "").replace("-", "")
            return Receiver(Receiver.stableId(key), name, address.getHostAddress(), port, deviceId)
        }

        @JvmStatic
        fun value(txt: MutableMap<String?, ByteArray?>, key: String?): String? {
            val value = txt.get(key)
            return if (value == null) "" else String(value, StandardCharsets.UTF_8)
        }

        @JvmStatic
        fun stableId(value: String): String? {
            try {
                val digest =
                    MessageDigest.getInstance("SHA-256")
                        .digest(value.toByteArray(StandardCharsets.UTF_8))
                val result = StringBuilder("homepod-")
                for (i in 0..11) result.append(
                    String.format(Locale.ROOT, "%02x", digest[i].toInt() and 255)
                )
                return result.toString()
            } catch (e: NoSuchAlgorithmException) {
                throw AssertionError(e)
            }
        }
    }

    override fun toString(): String =
        "Receiver[id=$id, name=$name, host=$host, port=$port, deviceId=$deviceId]"
}
