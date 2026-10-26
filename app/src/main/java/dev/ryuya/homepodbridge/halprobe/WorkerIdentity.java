package dev.ryuya.homepodbridge.halprobe;

import java.util.UUID;

/** Immutable identity authenticated before any FD handoff, rechecked immediately before SIGKILL. */
record WorkerIdentity(int pid, int parentPid, long startTicks, String commandSuffix) {
  static WorkerIdentity authenticate(
      int pid,
      int uid,
      int parent,
      String stat,
      String status,
      String command,
      String nonce,
      ProbeScenario scenario) {
    if (pid <= 0
        || parent <= 0
        || uid != 0
        || scenario == null
        || !UUID.fromString(nonce).toString().equals(nonce))
      throw new SecurityException("Invalid worker identity");
    String suffix =
        "dev.ryuya.homepodbridge.halprobe.HalQueueMapping\u0000hpb-map-"
            + nonce
            + "\u0000"
            + nonce
            + "\u0000"
            + parent
            + "\u0000"
            + scenario.workerMode
            + "\u0000";
    var identity = new WorkerIdentity(pid, parent, start(stat, pid, parent), suffix);
    if (!identity.matches(stat, status, command))
      throw new SecurityException("Unexpected worker process");
    return identity;
  }

  private static long start(String stat, int pid, int parent) {
    int space = stat.indexOf(' '), close = stat.lastIndexOf(')');
    if (space < 1 || close < space || !stat.substring(0, space).equals(Integer.toString(pid)))
      throw new SecurityException("Invalid process stat");
    String[] fields = stat.substring(close + 2).split("\\s+");
    if (fields.length < 20
        || !fields[1].equals(Integer.toString(parent))
        || !fields[19].matches("[1-9][0-9]*")) throw new SecurityException("Invalid worker epoch");
    return Long.parseLong(fields[19]);
  }

  boolean matches(String stat, String status, String command) {
    try {
      return start(stat, pid, parentPid) == startTicks
          && command.endsWith(commandSuffix)
          && status.matches("(?s).*\\nUid:\\s+0\\s+0\\s+0\\s+0\\s*\\n.*");
    } catch (RuntimeException invalid) {
      return false;
    }
  }
}
