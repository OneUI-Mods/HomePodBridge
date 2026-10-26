package dev.ryuya.homepodbridge.halprobe;

import static org.junit.Assert.*;

import org.junit.Test;

public final class WorkerIdentityTest {
  static final String NONCE = "12345678-1234-5678-1234-567812345678";
  static final String ROOT = "Name:\tapp_process\nUid:\t0\t0\t0\t0\n";
  static final String COMMAND =
      "app_process\u0000/system/bin\u0000dev.ryuya.homepodbridge.halprobe.HalQueueMapping\u0000hpb-map-"
          + NONCE
          + "\u0000"
          + NONCE
          + "\u000099\u0000hang\u0000";

  static String stat(int pid, int parent, int start) {
    String[] fields = new String[20];
    java.util.Arrays.fill(fields, "0");
    fields[0] = "S";
    fields[1] = Integer.toString(parent);
    fields[19] = Integer.toString(start);
    return pid + " (app process)) " + String.join(" ", fields);
  }

  static WorkerIdentity identity() {
    return WorkerIdentity.authenticate(
        123, 0, 99, stat(123, 99, 456), ROOT, COMMAND, NONCE, ProbeScenario.HANG);
  }

  @Test
  public void authenticatedWorkerMustKeepItsPidParentAndStartTime() {
    var identity = identity();
    assertTrue(identity.matches(stat(123, 99, 456), ROOT, COMMAND));
    assertFalse(identity.matches(stat(124, 99, 456), ROOT, COMMAND));
    assertFalse(identity.matches(stat(123, 98, 456), ROOT, COMMAND));
    assertFalse(identity.matches(stat(123, 99, 457), ROOT, COMMAND));
    assertFalse(identity.matches("123 broken", ROOT, COMMAND));
  }

  @Test
  public void systemUiHalAndOtherCommandsCanNeverReceiveSignal() {
    var identity = identity();
    assertFalse(
        identity.matches(
            stat(123, 99, 456), ROOT.replace("0\t0\t0\t0", "1000\t1000\t1000\t1000"), COMMAND));
    assertFalse(identity.matches(stat(123, 99, 456), ROOT, "com.android.systemui\u0000"));
    assertFalse(
        identity.matches(stat(123, 99, 456), ROOT, "/vendor/bin/hw/audiohalservice.qti\u0000"));
    assertFalse(identity.matches(stat(123, 99, 456), ROOT, COMMAND + "extra\u0000"));
    assertFalse(
        identity.matches(stat(123, 99, 456), ROOT, COMMAND.replace("hang\u0000", "none\u0000")));
    assertFalse(
        identity.matches(
            stat(123, 99, 456),
            ROOT,
            COMMAND.replace(NONCE, "22345678-1234-5678-1234-567812345678")));
  }

  @Test
  public void initialPeerCredentialsAndCommandAreRequired() {
    try {
      WorkerIdentity.authenticate(
          123, 2000, 99, stat(123, 99, 456), ROOT, COMMAND, NONCE, ProbeScenario.HANG);
      fail();
    } catch (SecurityException expected) {
    }
    try {
      WorkerIdentity.authenticate(
          123, 0, 99, stat(123, 99, 456), ROOT, COMMAND, NONCE, ProbeScenario.CONTROL);
      fail();
    } catch (SecurityException expected) {
    }
  }
}
