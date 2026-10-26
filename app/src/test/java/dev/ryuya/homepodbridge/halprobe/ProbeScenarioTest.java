package dev.ryuya.homepodbridge.halprobe;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ProbeScenarioTest {
  @Test
  public void faultNeedsMatchingCheckpointAndExitCode() {
    assertTrue(
        ProbeScenario.EXIT_FDS.acceptsFailure(73, false, false, "FAULT_CHECKPOINT=after-fds\n"));
    assertTrue(
        ProbeScenario.EXIT_MAP.acceptsFailure(
            73, false, false, "ready\nFAULT_CHECKPOINT=after-map\n"));
    assertTrue(
        ProbeScenario.EXIT_STATUS.acceptsFailure(
            74, false, false, "FAULT_CHECKPOINT=after-status\n"));
    assertFalse(
        ProbeScenario.EXIT_STATUS.acceptsFailure(
            73, false, false, "FAULT_CHECKPOINT=after-status\n"));
    assertFalse(
        ProbeScenario.EXIT_FDS.acceptsFailure(73, false, false, "FAULT_CHECKPOINT=after-map\n"));
    assertFalse(
        ProbeScenario.EXIT_FDS.acceptsFailure(73, true, true, "FAULT_CHECKPOINT=after-fds\n"));
    assertFalse(ProbeScenario.CONTROL.acceptsFailure(73, false, false, "FAULT_CHECKPOINT=none\n"));
    assertTrue(
        ProbeScenario.ZERO_INPUT_EXIT.acceptsFailure(
            75, false, false, "FAULT_CHECKPOINT=zero-input-exit\n"));
    assertFalse(
        ProbeScenario.ZERO_INPUT_EXIT.acceptsFailure(
            73, false, false, "FAULT_CHECKPOINT=zero-input-exit\n"));
    assertFalse(
        ProbeScenario.ZERO_1MS.acceptsFailure(73, false, false, "FAULT_CHECKPOINT=zero-1ms\n"));
  }

  @Test
  public void hangNeedsObservedTimeoutAndConfirmedForcedTermination() {
    assertTrue(ProbeScenario.HANG.acceptsFailure(137, true, true, "FAULT_CHECKPOINT=hang\n"));
    assertFalse(ProbeScenario.HANG.acceptsFailure(137, false, true, "FAULT_CHECKPOINT=hang\n"));
    assertFalse(ProbeScenario.HANG.acceptsFailure(137, true, false, "FAULT_CHECKPOINT=hang\n"));
    assertFalse(ProbeScenario.HANG.acceptsFailure(137, true, true, ""));
  }

  @Test
  public void callerCannotInjectOperationOrWorkerText() {
    assertNull(ProbeScenario.forCommand("probe-hal-streams; id"));
    assertNull(ProbeScenario.forOperation("streams; id"));
    assertNull(ProbeScenario.forWorker("hang; id"));
    assertNull(ProbeScenario.forCommand(null));
    for (var scenario : ProbeScenario.values()) {
      if (scenario == ProbeScenario.POLICY_FORWARD || scenario.live())
        assertNull(ProbeScenario.forCommand(scenario.command)); // PCM needs the dedicated reader.
      else assertSame(scenario, ProbeScenario.forCommand(scenario.command));
      assertSame(scenario, ProbeScenario.forOperation(scenario.operation));
      assertSame(scenario, ProbeScenario.forWorker(scenario.workerMode));
    }
  }
}
