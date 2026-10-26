package dev.ryuya.homepodbridge.halprobe;

/** Closed set of silent, no-PCM fault points; never accepts caller-supplied shell text. */
public enum ProbeScenario {
  CONTROL("probe-hal-streams", "streams", "none"),
  EXIT_FDS("probe-hal-exit-fds", "streams-exit-fds", "after-fds"),
  EXIT_MAP("probe-hal-exit-map", "streams-exit-map", "after-map"),
  EXIT_STATUS("probe-hal-exit-status", "streams-exit-status", "after-status"),
  HANG("probe-hal-worker-hang", "streams-worker-hang", "hang");

  public final String command;
  public final String operation;
  final String workerMode;

  ProbeScenario(String command, String operation, String workerMode) {
    this.command = command;
    this.operation = operation;
    this.workerMode = workerMode;
  }

  public static ProbeScenario forCommand(String value) {
    for (var scenario : values()) if (scenario.command.equals(value)) return scenario;
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
    if (this == HANG) return timedOut && forced;
    return !timedOut && !forced && exitCode == (this == EXIT_STATUS ? 74 : 73);
  }
}
