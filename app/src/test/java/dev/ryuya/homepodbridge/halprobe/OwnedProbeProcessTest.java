package dev.ryuya.homepodbridge.halprobe;

import static org.junit.Assert.*;

import org.junit.Test;

public final class OwnedProbeProcessTest {
  @Test
  public void helperCleanupCannotSelectSystemUiHalOrAnotherNonce() {
    String nonce = "12345678-1234-5678-1234-567812345678";
    String command =
        "app_process\u0000/system/bin\u0000dev.ryuya.homepodbridge.halprobe.HalPortProbe\u0000streams\u0000"
            + nonce
            + "\u000044100\u0000";
    String root = "Name:\tapp_process\nUid:\t0\t0\t0\t0\n";
    assertTrue(OwnedProbeProcess.matches(command, root, nonce));
    assertFalse(OwnedProbeProcess.matches("com.android.systemui\u0000", root, nonce));
    assertFalse(OwnedProbeProcess.matches("/vendor/bin/hw/audiohalservice.qti\u0000", root, nonce));
    assertFalse(
        OwnedProbeProcess.matches(
            command.replace(nonce, "22345678-1234-5678-1234-567812345678"), root, nonce));
    assertFalse(
        OwnedProbeProcess.matches(
            command, root.replace("0\t0\t0\t0", "2000\t2000\t2000\t2000"), nonce));
    assertFalse(OwnedProbeProcess.matches(command + "extra\u0000", root, nonce));
    assertFalse(OwnedProbeProcess.matches(command, root, nonce + "; id"));
  }
}
