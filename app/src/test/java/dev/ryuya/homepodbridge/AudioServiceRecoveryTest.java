package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class AudioServiceRecoveryTest {
  private static final String NONCE = "12345678-1234-5678-1234-567812345678";
  private static final String DUMP =
      "SubmixRoutes:\n - AudioDeviceAddress{id: HomePodBridgeDirect:"
          + NONCE
          + "}: Input closed, standby, refcount: 0, framesRead: 0; Output open, standby,"
          + " framesWritten: 0\n";

  @Test
  public void refusesForeignOrActiveRoutesBeforeSharedServiceRestart() {
    assertTrue(AudioServiceRecovery.onlyUnstartedOwnedRoute(DUMP, NONCE));
    assertFalse(AudioServiceRecovery.onlyUnstartedOwnedRoute(DUMP + DUMP, NONCE));
    assertFalse(
        AudioServiceRecovery.onlyUnstartedOwnedRoute(
            DUMP.replace("framesWritten: 0", "framesWritten: 1"), NONCE));
    assertFalse(
        AudioServiceRecovery.onlyUnstartedOwnedRoute(
            DUMP.replace("Input closed", "Input open"), NONCE));
    assertFalse(
        AudioServiceRecovery.onlyUnstartedOwnedRoute(
            DUMP.replace("HomePodBridgeDirect:", "AnotherApp:"), NONCE));
    assertFalse(
        AudioServiceRecovery.onlyUnstartedOwnedRoute(DUMP, "22345678-1234-5678-1234-567812345678"));
    assertFalse(AudioServiceRecovery.onlyUnstartedOwnedRoute("SubmixRoutes:\n<Empty>", NONCE));
  }

  @Test
  public void nonceCannotSupplyShellTextOrAlternateUuidSpellings() {
    assertFalse(AudioServiceRecovery.validNonce(null));
    assertFalse(AudioServiceRecovery.validNonce(NONCE + "; id"));
    assertFalse(AudioServiceRecovery.validNonce("1-1-1-1-1"));
    assertFalse(AudioServiceRecovery.validNonce(""));
  }
}
