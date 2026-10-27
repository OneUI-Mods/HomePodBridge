package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public class ReceiverRejectionTest {
  @Test
  public void userNoticeDistinguishesRejectionCompatibilityAndLastAttempt() {
    assertEquals(
        R.string.receiver_request_rejected,
        new ReceiverRejection(400, true).messageResource(false));
    assertEquals(
        R.string.receiver_request_compatibility,
        new ReceiverRejection(400, false).messageResource(true));
    assertEquals(
        R.string.receiver_request_last_retry,
        new ReceiverRejection(400, false).messageResource(false));
    assertEquals(0, ReceiverRejection.NONE.messageResource(false));
  }

  @Test
  public void receiverReplyDistinguishesRetryAndTerminalRefusal() {
    assertEquals(
        new ReceiverRejection(400, false), ReceiverRejection.fromNative(new int[] {400, 0}));
    assertEquals(
        new ReceiverRejection(415, true), ReceiverRejection.fromNative(new int[] {415, 1}));
    assertEquals(
        new ReceiverRejection(403, true), ReceiverRejection.fromNative(new int[] {403, 1}));
  }

  @Test
  public void missingMalformedAndSuccessfulRepliesCannotBecomeRejection() {
    for (int[] value :
        new int[][] {
          null, {}, {400}, {200, 0}, {0, 1}, {399, 1}, {600, 1}, {400, -1}, {400, 2}, {400, 0, 1}
        }) assertEquals(ReceiverRejection.NONE, ReceiverRejection.fromNative(value));
    assertThrows(IllegalArgumentException.class, () -> new ReceiverRejection(0, true));
  }
}
