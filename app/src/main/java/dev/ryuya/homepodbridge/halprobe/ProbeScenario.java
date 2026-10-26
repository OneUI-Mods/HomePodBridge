package dev.ryuya.homepodbridge.halprobe;

/** Closed set of silent virtual-path diagnostics; never accepts caller audio or shell text. */
public enum ProbeScenario {
  CONTROL("probe-hal-streams", "streams", "none"),
  EXIT_FDS("probe-hal-exit-fds", "streams-exit-fds", "after-fds"),
  EXIT_MAP("probe-hal-exit-map", "streams-exit-map", "after-map"),
  EXIT_STATUS("probe-hal-exit-status", "streams-exit-status", "after-status"),
  HANG("probe-hal-worker-hang", "streams-worker-hang", "hang"),
  ZERO_21MS("probe-hal-zero-21ms", "streams-zero-21ms", "zero-21ms", 928),
  ZERO_2MS("probe-hal-zero-2ms", "streams-zero-2ms", "zero-2ms", 88),
  ZERO_1MS("probe-hal-zero-1ms", "streams-zero-1ms", "zero-1ms", 44),
  ZERO_HALF_MS("probe-hal-zero-half-ms", "streams-zero-half-ms", "zero-half-ms", 22),
  ZERO_INPUT_EXIT("probe-hal-zero-input-exit", "streams-zero-input-exit", "zero-input-exit", 928),
  ZERO_OUTPUT_BLOCK(
      "probe-hal-zero-output-block", "streams-zero-output-block", "zero-output-block", 928),
  POLICY_INPUT("probe-policy-capture", "policy-capture", "policy-capture"),
  POLICY_FAST("probe-policy-capture-fast", "policy-capture-fast", "policy-capture-fast"),
  POLICY_MINIMUM(
      "probe-policy-capture-minimum", "policy-capture-minimum", "policy-capture-minimum"),
  POLICY_SMALLER(
      "probe-policy-capture-smaller", "policy-capture-smaller", "policy-capture-smaller"),
  POLICY_FORWARD("probe-policy-forward", "policy-forward", "policy-forward"),
  LIVE("internal-live", "live", "live"),
  LIVE_ZERO("internal-live-zero", "live-zero", "live-zero");

  boolean live() {
    return this == LIVE || this == LIVE_ZERO;
  }

  boolean policyInput() {
    return this == POLICY_INPUT
        || this == POLICY_FAST
        || this == POLICY_MINIMUM
        || this == POLICY_SMALLER
        || this == POLICY_FORWARD
        || live();
  }

  public final String command;
  public final String operation;
  final String workerMode;
  final int zeroFrames;

  ProbeScenario(String command, String operation, String workerMode) {
    this(command, operation, workerMode, 0);
  }

  ProbeScenario(String command, String operation, String workerMode, int zeroFrames) {
    this.command = command;
    this.operation = operation;
    this.workerMode = workerMode;
    this.zeroFrames = zeroFrames;
  }

  public static ProbeScenario forCommand(String value) {
    for (var scenario : values())
      if (scenario != POLICY_FORWARD && !scenario.live() && scenario.command.equals(value))
        return scenario;
    return null;
  }

  static ProbeScenario forOperation(String value) {
    for (var scenario : values()) if (scenario.operation.equals(value)) return scenario;
    return null;
  }

  static ProbeScenario forWorker(String value) {
    for (var scenario : values()) if (scenario.workerMode.equals(value)) return scenario;
    return null;
  }

  boolean acceptsFailure(int exitCode, boolean timedOut, boolean forced, String diagnostics) {
    if (this == CONTROL
        || !("\n" + diagnostics).contains("\nFAULT_CHECKPOINT=" + workerMode + "\n")) return false;
    if (this == HANG) return timedOut && forced && exitCode == 137;
    if (this == ZERO_INPUT_EXIT) return !timedOut && !forced && exitCode == 75;
    if (zeroFrames > 0) return false;
    return !timedOut && !forced && exitCode == (this == EXIT_STATUS ? 74 : 73);
  }
}
