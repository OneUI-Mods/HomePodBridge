package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;

public final class OwnedAudioPathTest {
  @Test
  public void aRefusedWriteDoesNotRestoreAnIndependentExistingTarget() {
    Queue queue = new Queue();
    List<Integer> calls = new ArrayList<>();
    var lease =
        new OwnedAudioPath(
            op -> {
              calls.add(op);
              return "Read-only owned route verified\n"
                         + "Owned HAL operation refused: stale owned target; writeAttempts=0";
            },
            queue,
            value -> {});
    lease.start();
    queue.drain();
    lease.close();
    queue.drain();
    assertEquals(List.of(2), calls);
  }

  private static final String APPLIED =
      "Read-only owned route verified\nOwned reserve changed: 2587 -> 1856";
  private static final String RESTORED = "Owned reserve changed: 1856 -> 2587";

  private static final class Queue implements Executor {
    final ArrayDeque<Runnable> pending = new ArrayDeque<>();

    public void execute(Runnable task) {
      pending.add(task);
    }

    void drain() {
      while (!pending.isEmpty()) pending.remove().run();
    }
  }

  @Test
  public void activeLeaseRestoresOnceOnCloseAndDoesNotPublishAfterClose() {
    Queue queue = new Queue();
    List<Integer> calls = new ArrayList<>();
    List<OwnedAudioPath.Update> states = new ArrayList<>();
    var lease =
        new OwnedAudioPath(
            op -> {
              calls.add(op);
              return op == 2 ? APPLIED : RESTORED;
            },
            queue,
            states::add);
    lease.start();
    lease.start();
    queue.drain();
    assertEquals(OwnedAudioPath.State.ACTIVE, states.get(states.size() - 1).state());
    int size = states.size();
    lease.close();
    lease.close();
    queue.drain();
    assertEquals(List.of(2, 3), calls);
    assertEquals(size, states.size());
  }

  @Test
  public void closedBeforeDispatchNeverTouchesHardware() {
    Queue queue = new Queue();
    List<Integer> calls = new ArrayList<>();
    var lease =
        new OwnedAudioPath(
            op -> {
              calls.add(op);
              return APPLIED;
            },
            queue,
            u -> fail());
    lease.start();
    lease.close();
    queue.drain();
    assertTrue(calls.isEmpty());
  }

  @Test
  public void clientDeathDuringApplyRestoresWithoutPublishingActive() {
    Queue queue = new Queue();
    List<Integer> calls = new ArrayList<>();
    List<OwnedAudioPath.Update> states = new ArrayList<>();
    OwnedAudioPath[] holder = new OwnedAudioPath[1];
    holder[0] =
        new OwnedAudioPath(
            op -> {
              calls.add(op);
              if (op == 2) {
                holder[0].close();
                return APPLIED;
              }
              return RESTORED;
            },
            queue,
            states::add);
    holder[0].start();
    queue.drain();
    assertEquals(List.of(2, 3), calls);
    assertFalse(states.stream().anyMatch(u -> u.state() == OwnedAudioPath.State.ACTIVE));
  }

  @Test
  public void unsupportedDeviceKeepsExistingCaptureWithoutRestorationAttempts() {
    Queue queue = new Queue();
    List<Integer> calls = new ArrayList<>();
    List<OwnedAudioPath.Update> states = new ArrayList<>();
    var lease =
        new OwnedAudioPath(
            op -> {
              calls.add(op);
              return "Read-only route probe unavailable: Unsupported HAL build";
            },
            queue,
            states::add);
    lease.start();
    queue.drain();
    lease.close();
    queue.drain();
    assertEquals(List.of(2), calls);
    assertEquals(OwnedAudioPath.State.STANDARD, states.get(states.size() - 1).state());
  }

  @Test
  public void failedMutationStageMustRestoreBeforeFallback() {
    Queue queue = new Queue();
    List<Integer> calls = new ArrayList<>();
    List<OwnedAudioPath.Update> states = new ArrayList<>();
    var lease =
        new OwnedAudioPath(
            op -> {
              calls.add(op);
              return op == 2
                  ? "Read-only owned route verified\noperation refused"
                  : "Original reserve already present";
            },
            queue,
            states::add);
    lease.start();
    queue.drain();
    assertEquals(List.of(2, 3), calls);
    assertEquals(OwnedAudioPath.State.STANDARD, states.get(states.size() - 1).state());
  }

  @Test
  public void lostReplyAndUnconfirmedRollbackRequireOwnedCaptureRecovery() {
    Queue queue = new Queue();
    List<OwnedAudioPath.Update> states = new ArrayList<>();
    var lease =
        new OwnedAudioPath(
            op -> {
              throw new java.io.IOException("Lost reply");
            },
            queue,
            states::add);
    lease.start();
    queue.drain();
    assertEquals(OwnedAudioPath.State.RECOVERY_REQUIRED, states.get(states.size() - 1).state());
  }
}
