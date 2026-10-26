package dev.ryuya.homepodbridge;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Explicit, DUMP-protected recovery of a stranded, unstarted prototype route. */
final class AudioServiceRecovery {
  static boolean validNonce(String value) {
    try {
      return value != null && UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  static boolean onlyUnstartedOwnedRoute(String dump, String nonce) {
    if (!validNonce(nonce)) return false;
    String expected =
        "SubmixRoutes:\n - AudioDeviceAddress{id: HomePodBridgeDirect:"
            + nonce
            + "}: Input closed, standby, refcount: 0, framesRead: 0;"
            + " Output open, standby, framesWritten: 0";
    return dump.replace("\r", "").trim().equals(expected);
  }

  static boolean onlyOwnedZeroRoute(String dump, String nonce) {
    if (!validNonce(nonce)) return false;
    String prefix =
        java.util.regex.Pattern.quote(
            "SubmixRoutes:\n - AudioDeviceAddress{id: HomePodBridgeDirect:" + nonce + "}: Input ");
    return dump.replace("\r", "")
        .trim()
        .matches(
            prefix
                + "(?:closed, (?:standby|active), refcount: 0|open, (?:standby|active), refcount:"
                + " 1), framesRead: [0-9]+; Output (?:open|closed), (?:standby|active),"
                + " framesWritten: [0-9]+");
  }

  private static boolean ownsZeroJournal(String nonce, String pid, String stat) throws Exception {
    Path journal = Path.of("/data/local/tmp/homepod-direct-" + nonce + ".json");
    if (Files.isSymbolicLink(journal)
        || Files.size(journal) > 65536
        || ((Number)
                    Files.getAttribute(
                        journal, "unix:uid", java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .intValue()
            != 0) return false;
    var saved = new org.json.JSONObject(readProc(journal));
    String start = stat.substring(stat.lastIndexOf(')') + 2).split("\\s+")[19];
    return saved.optBoolean("zeroOnly", false)
        && (pid + ":" + start).equals(saved.getString("epoch"));
  }

  static String restart(Context context, String nonce) {
    if (!validNonce(nonce) || BridgeState.connected || BridgeState.volume != 0)
      return "RECOVERY_REFUSED";
    try {
      String apk = context.getApplicationInfo().sourceDir;
      if (!apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk")) return "RECOVERY_REFUSED";
      return run(
              List.of(
                  "su",
                  "-c",
                  "CLASSPATH='"
                      + apk
                      + "' exec app_process /system/bin"
                      + " dev.ryuya.homepodbridge.AudioServiceRecovery "
                      + nonce),
              256,
              20)
          .trim();
    } catch (Exception unavailable) {
      return "RECOVERY_UNAVAILABLE";
    }
  }

  static String run(List<String> args, int maximum, int seconds) throws Exception {
    return run(args, maximum, seconds, false);
  }

  static String run(List<String> args, int maximum, int seconds, boolean retainFailure)
      throws Exception {
    Process process =
        new ProcessBuilder(args)
            .redirectError(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")))
            .start();
    var watchdog = Executors.newSingleThreadScheduledExecutor();
    var timeout = watchdog.schedule(process::destroyForcibly, seconds, TimeUnit.SECONDS);
    try {
      byte[] output = process.getInputStream().readNBytes(maximum + 1);
      if (output.length > maximum
          || !process.waitFor(seconds, TimeUnit.SECONDS)
          || (!retainFailure && process.exitValue() != 0))
        throw new java.io.IOException("Recovery command unavailable");
      return new String(output, StandardCharsets.UTF_8);
    } finally {
      timeout.cancel(false);
      watchdog.shutdownNow();
      process.destroy();
    }
  }

  private static String readProc(Path path) throws java.io.IOException {
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  private static String modulePid() throws Exception {
    String pid =
        run(List.of("dumpsys", "--pid", "android.hardware.audio.core.IModule/r_submix"), 128, 3)
            .trim();
    if (!pid.matches("[1-9][0-9]{0,6}")) throw new java.io.IOException("No module process");
    return pid;
  }

  public static void main(String[] args) {
    try {
      if (args.length != 1 || !validNonce(args[0])) throw new IllegalArgumentException();
      String pid = modulePid();
      Path proc = Path.of("/proc", pid);
      String executable = readProc(proc.resolve("cmdline"));
      String identity = readProc(proc.resolve("status"));
      if (!executable.split("\u0000")[0].equals("/vendor/bin/hw/audiohalservice.qti")
          || !identity.matches("(?s).*\\nUid:\\s+1041\\s+1041\\s+1041\\s+1041\\s*\\n.*"))
        throw new SecurityException();
      String epoch = readProc(proc.resolve("stat"));
      String mode = run(List.of("dumpsys", "audio"), 1048576, 4);
      if (!mode.contains("mAudioModeOwner: AudioDeviceBroker$AudioModeInfo[mMode=0,"))
        throw new SecurityException();
      String routes =
          run(List.of("dumpsys", "android.hardware.audio.core.IModule/r_submix"), 8192, 3);
      if (!onlyUnstartedOwnedRoute(routes, args[0])
          && !(onlyOwnedZeroRoute(routes, args[0]) && ownsZeroJournal(args[0], pid, epoch)))
        throw new SecurityException();
      if (!"running"
              .equals(run(List.of("getprop", "init.svc.vendor.audio-hal-aidl"), 128, 2).trim())
          || !pid.equals(modulePid())) throw new SecurityException();
      String currentStat = readProc(proc.resolve("stat"));
      // PID reuse guard: compare the process start-time field, rather than changing counters.
      if (!epoch
          .substring(epoch.lastIndexOf(')') + 2)
          .split("\\s+")[19]
          .equals(currentStat.substring(currentStat.lastIndexOf(')') + 2).split("\\s+")[19]))
        throw new SecurityException();
      run(List.of("setprop", "ctl.restart", "vendor.audio-hal-aidl"), 128, 2);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
      while (System.nanoTime() < deadline) {
        try {
          if (!pid.equals(modulePid())
              && run(List.of("dumpsys", "android.hardware.audio.core.IModule/r_submix"), 8192, 2)
                  .contains("<Empty>")) {
            System.out.println("RECOVERY_COMPLETE");
            return;
          }
        } catch (Exception restarting) {
          /* Init temporarily removes the service. */
        }
        Thread.sleep(200);
      }
      System.out.println("RECOVERY_NOT_CONFIRMED");
    } catch (Exception refused) {
      System.out.println("RECOVERY_REFUSED");
    }
  }

  private AudioServiceRecovery() {}
}
