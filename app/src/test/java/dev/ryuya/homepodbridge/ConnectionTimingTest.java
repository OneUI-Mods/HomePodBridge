package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class ConnectionTimingTest {
  @Test
  public void overlappingStagesRemainSeparateFromTotal() {
    var now = new AtomicLong(1000000000L);
    var timing = new ConnectionTiming(now::get);
    now.set(1001000000L);
    timing.mark(ConnectionTiming.Stage.WORKER);
    now.set(1003000000L);
    timing.mark(ConnectionTiming.Stage.NETWORK);
    now.set(1004000000L);
    timing.mark(ConnectionTiming.Stage.SENDER);
    timing.markAt(ConnectionTiming.Stage.AUTHENTICATED, 1007000000L);
    now.set(1011000000L);
    timing.mark(ConnectionTiming.Stage.CAPTURE);
    now.set(1012000000L);
    timing.mark(ConnectionTiming.Stage.READY);
    assertEquals(
        8000, timing.micros(ConnectionTiming.Stage.NETWORK, ConnectionTiming.Stage.CAPTURE));
    assertEquals(
        3000, timing.micros(ConnectionTiming.Stage.SENDER, ConnectionTiming.Stage.AUTHENTICATED));
    assertEquals(
        12000, timing.micros(ConnectionTiming.Stage.SELECTED, ConnectionTiming.Stage.READY));
  }

  @Test
  public void unfinishedInvalidAndRepeatedEventsCannotInventDurations() {
    var now = new AtomicLong(1000000000L);
    var timing = new ConnectionTiming(now::get);
    assertEquals(-1, timing.micros(ConnectionTiming.Stage.SELECTED, ConnectionTiming.Stage.READY));
    timing.markAt(ConnectionTiming.Stage.READY, 1);
    assertEquals(-1, timing.micros(ConnectionTiming.Stage.SELECTED, ConnectionTiming.Stage.READY));
    now.set(1004000000L);
    timing.mark(ConnectionTiming.Stage.READY);
    now.set(1009000000L);
    timing.mark(ConnectionTiming.Stage.READY);
    assertEquals(
        4000, timing.micros(ConnectionTiming.Stage.SELECTED, ConnectionTiming.Stage.READY));
  }
}
