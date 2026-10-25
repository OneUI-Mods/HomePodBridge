package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class ReceiverTest {
    private Map<String, byte[]> txt(String model) {
        Map<String, byte[]> attributes = new HashMap<>();
        attributes.put("am", model.getBytes(StandardCharsets.UTF_8));
        return attributes;
    }
    @Test public void homePodIdentitySurvivesNameAndAddressChanges() throws Exception {
        Receiver first = Receiver.from("AABBCCDDEEFF@Living room", InetAddress.getByName("192.168.1.10"), 7000, txt("AudioAccessory5,1"));
        var moved = txt("AudioAccessory5,1");
        moved.put("deviceid", "AA:BB:CC:DD:EE:FF".getBytes(StandardCharsets.UTF_8));
        Receiver second = Receiver.from("Bedroom", InetAddress.getByName("192.168.1.20"), 7001, moved);
        assertNotNull(first); assertNotNull(second);
        assertEquals(first.id(), second.id());
        assertEquals("Living room", first.name());
        assertEquals(7001, second.port());
    }
    @Test public void unsupportedReceiverAndPasswordAreNotAdvertised() throws Exception {
        var address = InetAddress.getByName("192.168.1.10");
        assertNull(Receiver.from("AABBCC@TV", address, 7000, txt("AppleTV14,1")));
        var attributes = txt("AudioAccessory5,1");
        attributes.put("pw", "TRUE".getBytes(StandardCharsets.UTF_8));
        assertNull(Receiver.from("AABBCC@HomePod", address, 7000, attributes));
    }
    @Test public void invalidEndpointsAndMissingIdentityAreRejected() throws Exception {
        assertNull(Receiver.from("AABBCC@HomePod", InetAddress.getByName("::1"), 7000, txt("AudioAccessory5,1")));
        assertNull(Receiver.from("AABBCC@HomePod", InetAddress.getByName("192.168.1.10"), -1, txt("AudioAccessory5,1")));
        assertNull(Receiver.from("HomePod", InetAddress.getByName("192.168.1.10"), 7000, txt("AudioAccessory5,1")));
    }
}
