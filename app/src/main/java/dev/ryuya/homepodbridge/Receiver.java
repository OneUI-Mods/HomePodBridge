package dev.ryuya.homepodbridge;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;

record Receiver(String id, String name, String host, int port, String deviceId) {
  static Receiver from(String serviceName, InetAddress address, int port, Map<String, byte[]> txt) {
    if (!(address instanceof Inet4Address) || port <= 0 || port > 65535) return null;
    String model = value(txt, "am");
    if (!model.startsWith("AudioAccessory") && !model.toLowerCase(Locale.ROOT).contains("homepod"))
      return null;
    if ("true".equalsIgnoreCase(value(txt, "pw"))) return null;
    String name = serviceName;
    String deviceId = value(txt, "deviceid");
    int at = name.indexOf('@');
    if (at > 0) {
      if (deviceId.isEmpty()) deviceId = name.substring(0, at);
      name = name.substring(at + 1);
    }
    if (deviceId.isEmpty()) return null;
    String key = deviceId.toUpperCase(Locale.ROOT).replace(":", "").replace("-", "");
    return new Receiver(stableId(key), name, address.getHostAddress(), port, deviceId);
  }

  static String value(Map<String, byte[]> txt, String key) {
    byte[] value = txt.get(key);
    return value == null ? "" : new String(value, StandardCharsets.UTF_8);
  }

  static String stableId(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder("homepod-");
      for (int i = 0; i < 12; i++)
        result.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
      return result.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
