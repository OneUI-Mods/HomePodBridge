package dev.ryuya.homepodbridge

import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.HashMap
import org.junit.Assert.*
import org.junit.Test

class ReceiverTest {
    private fun txt(model: String): MutableMap<String?, ByteArray?> {
        val attributes = HashMap<String?, ByteArray?>()
        attributes.put("am", model.toByteArray(StandardCharsets.UTF_8))
        return attributes
    }

    @Test
    @Throws(Exception::class)
    fun homePodIdentitySurvivesNameAndAddressChanges() {
        val first =
            Receiver.from(
                "AABBCCDDEEFF@Living room",
                InetAddress.getByName("192.168.1.10"),
                7000,
                txt("AudioAccessory5,1"),
            )
        val moved = txt("AudioAccessory5,1")
        moved.put("deviceid", "AA:BB:CC:DD:EE:FF".toByteArray(StandardCharsets.UTF_8))
        val second = Receiver.from("Bedroom", InetAddress.getByName("192.168.1.20"), 7001, moved)
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first!!.id, second!!.id)
        assertEquals("Living room", first!!.name)
        assertEquals(7001, second!!.port)
    }

    @Test
    @Throws(Exception::class)
    fun unsupportedReceiverAndPasswordAreNotAdvertised() {
        val address = InetAddress.getByName("192.168.1.10")
        assertNull(Receiver.from("AABBCC@TV", address, 7000, txt("AppleTV14,1")))
        val attributes = txt("AudioAccessory5,1")
        attributes.put("pw", "TRUE".toByteArray(StandardCharsets.UTF_8))
        assertNull(Receiver.from("AABBCC@HomePod", address, 7000, attributes))
    }

    @Test
    @Throws(Exception::class)
    fun invalidEndpointsAndMissingIdentityAreRejected() {
        assertNull(
            Receiver.from(
                "AABBCC@HomePod",
                InetAddress.getByName("::1"),
                7000,
                txt("AudioAccessory5,1"),
            )
        )
        assertNull(
            Receiver.from(
                "AABBCC@HomePod",
                InetAddress.getByName("192.168.1.10"),
                -1,
                txt("AudioAccessory5,1"),
            )
        )
        assertNull(
            Receiver.from(
                "HomePod",
                InetAddress.getByName("192.168.1.10"),
                7000,
                txt("AudioAccessory5,1"),
            )
        )
    }
}
