package dev.ryuya.homepodbridge.halprobe;

import android.content.Context;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Closed self-UID, no-playback HAL getter trial. No executable/data bytes are patched. */
final class HalPeriodExperiment implements AutoCloseable {
  private final Path directory;
  private final Process process;
  private final Thread control;
  private final CountDownLatch ready = new CountDownLatch(1);
  private final StringBuilder transcript = new StringBuilder();
  private volatile boolean stopping;
  private boolean closed;
  private boolean cleanupConfirmed;

  boolean cleanupConfirmed() {
    return cleanupConfirmed;
  }

  private volatile String failure;
  private volatile int accepted;

  HalPeriodExperiment(String address, boolean mixer) throws Exception {
    String apk = System.getenv("CLASSPATH");
    if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk"))
      throw new SecurityException("Installed helper required");
    byte[] bytes;
    try (var archive = new java.util.zip.ZipFile(apk)) {
      var entry = archive.getEntry("lib/arm64-v8a/libhomepod_period_tool.so");
      if (entry == null || entry.getSize() > 1048576)
        throw new SecurityException("Packaged trial tool missing");
      try (var input = archive.getInputStream(entry)) {
        bytes = input.readNBytes(1048577);
      }
    }
    if (bytes.length > 1048576
        || bytes.length < 64
        || bytes[0] != 127
        || bytes[1] != 'E'
        || bytes[2] != 'L'
        || bytes[3] != 'F') throw new SecurityException("Invalid packaged executable");
    if (mixer) {
      byte[] library = Files.readAllBytes(Path.of("/system/bin/audioserver"));
      if (library.length > 4194304
          || !HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(library))
              .equals("78d47cf72768eff61e5e47ad96c5cd10e6779b97301cf01c135bccda05c9e201"))
        throw new SecurityException("Unsupported mixer build");
    }
    directory =
        Files.createTempDirectory(
            Path.of("/data/local/tmp"),
            "homepod-getter-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Path executable = directory.resolve("probe");
    Process started = null;
    try {
      Files.write(executable, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
      Process query =
          new ProcessBuilder(
                  "dumpsys",
                  "--pid",
                  mixer ? "media.audio_flinger" : ReadOnlyHalState.DESCRIPTOR + "/r_submix")
              .start();
      String pid =
          new String(query.getInputStream().readNBytes(128), StandardCharsets.US_ASCII).trim();
      if (!query.waitFor(2, TimeUnit.SECONDS) || !pid.matches("[1-9][0-9]{0,6}"))
        throw new SecurityException();
      String base = null;

      for (String line : Files.readAllLines(Path.of("/proc", pid, "maps"))) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length == 6
            && fields[2].equals("00000000")
            && fields[5].equals(
                mixer
                    ? "/system/bin/audioserver"
                    : "/vendor/lib64/libaudioserviceexampleimpl.so")) {
          if (base != null) throw new SecurityException("Ambiguous module");
          base = fields[0].split("-")[0];
        }
      }
      if (base == null) throw new SecurityException("Pinned module missing");
      started =
          new ProcessBuilder(
                  mixer
                      ? java.util.List.of(executable.toString(), pid, base, "mixer")
                      : java.util.List.of(executable.toString(), pid, base))
              .redirectErrorStream(true)
              .start();
    } catch (Exception error) {
      if (started != null) started.destroyForcibly();
      Files.deleteIfExists(executable);
      Files.deleteIfExists(directory);
      throw error;
    }
    process = started;
    control =
        new Thread(
            () -> {
              try (var reader =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  if (line.length() > 256) throw new SecurityException("Invalid trace reply");
                  synchronized (transcript) {
                    if (transcript.length() < 8192) transcript.append(line).append('\n');
                  }
                  if (line.startsWith("READY ")) ready.countDown();
                  if (line.startsWith("HIT ")) {
                    String[] fields = line.split(" ");
                    if (fields.length != 4) throw new SecurityException("Invalid getter event");
                    int sequence = Integer.parseInt(fields[1]),
                        id = Integer.parseInt(fields[2]),
                        port = Integer.parseInt(fields[3]);
                    boolean allow =
                        !stopping
                            && (mixer
                                ? ownedMixer(new ReadOnlyHalState().snapshot(), address, id)
                                : ownedConfig(
                                    new ReadOnlyHalState().snapshot(), address, id, port));
                    if (allow) accepted++;
                    process
                        .getOutputStream()
                        .write(
                            ((allow ? "A " : "P ") + sequence + "\n")
                                .getBytes(StandardCharsets.US_ASCII));
                    process.getOutputStream().flush();
                  }
                }
              } catch (Exception error) {
                failure = error.getClass().getSimpleName() + ": " + error.getMessage();
              } finally {
                ready.countDown();
              }
            },
            "HomePod-owned-getter-trial");
    control.setDaemon(true);
    control.start();
    if (!ready.await(3, TimeUnit.SECONDS) || failure != null || !process.isAlive()) {
      close();
      throw new IllegalStateException("Getter trial not ready: " + transcript);
    }
  }

  static boolean ownedConfig(JSONObject snapshot, String address, int id, int port)
      throws Exception {
    var ports = new java.util.ArrayList<PeriodRouteGuard.Port>();
    var configs = new java.util.ArrayList<PeriodRouteGuard.Config>();
    JSONArray rawPorts = snapshot.getJSONArray("ports"),
        rawConfigs = snapshot.getJSONArray("configs");
    for (int i = 0; i < rawPorts.length(); i++) {
      JSONObject p = rawPorts.getJSONObject(i);
      ports.add(
          new PeriodRouteGuard.Port(
              p.getInt("id"), p.optInt("deviceType"), p.optString("address")));
    }
    for (int i = 0; i < rawConfigs.length(); i++) {
      JSONObject c = rawConfigs.getJSONObject(i);
      configs.add(
          new PeriodRouteGuard.Config(
              c.getInt("id"),
              c.getInt("portId"),
              c.getInt("rate"),
              c.getBoolean("stereo"),
              c.getBoolean("pcm16"),
              c.optInt("handle")));
    }
    return PeriodRouteGuard.allows(ports, configs, address, id, port);
  }

  private static boolean ownedMixer(JSONObject snapshot, String address, int io) throws Exception {
    if (io <= 0) return false;
    JSONArray configs = snapshot.getJSONArray("configs"),
        patches = snapshot.getJSONArray("patches");
    for (int i = 0; i < configs.length(); i++) {
      JSONObject c = configs.getJSONObject(i);
      if (c.optInt("handle") == io
          && c.getInt("portId") == 3
          && ownedConfig(snapshot, address, c.getInt("id"), 3)) {
        for (int j = 0; j < patches.length(); j++) {
          JSONObject p = patches.getJSONObject(j);
          JSONArray sources = p.getJSONArray("sources");
          if (sources.length() == 1
              && sources.getInt(0) == c.getInt("id")
              && p.getInt("minimumFrames") == 96) return true;
        }
      }
    }
    return false;
  }

  static JSONObject requireSmallOutput(ReadOnlyHalState state, String address) throws Exception {
    int io = OutputPeriodProbe.ownedOutput(state.snapshot(), address);
    Process dump = new ProcessBuilder("dumpsys", "media.audio_flinger").start();
    byte[] bytes = dump.getInputStream().readNBytes(1048577);
    if (!dump.waitFor(2, TimeUnit.SECONDS) || bytes.length > 1048576) {
      dump.destroyForcibly();
      throw new SecurityException("Mixer period unavailable");
    }
    for (String section :
        new String(bytes, StandardCharsets.UTF_8).replace("\r", "").split("Output thread ")) {
      boolean matched = false;
      for (String line : section.split("\n"))
        if (line.equals("  I/O handle: " + io)) matched = true;
      if (!matched) continue;
      int hal = 0, normal = 0, rate = 0;
      for (String line : section.split("\n")) {
        if (line.trim().startsWith("-")) break;
        String text = line.trim();
        if (text.startsWith("HAL frame count: ")) hal = Integer.parseInt(text.substring(17).trim());
        if (text.startsWith("Normal frame count: "))
          normal = Integer.parseInt(text.substring(20).trim());
        if (text.startsWith("Sample rate: "))
          rate = Integer.parseInt(text.substring(13).split(" ")[0]);
      }
      if (hal == 96 && normal == 96 && rate == 44100)
        return new JSONObject()
            .put("halFrames", hal)
            .put("normalFrames", normal)
            .put("sampleRate", rate);
    }
    throw new SecurityException("Short period not confirmed");
  }

  static JSONObject run() throws Exception {
    return run(false, false);
  }

  static JSONObject run(boolean mixer, boolean capture) throws Exception {
    HalPortProbe.rootPreflight();
    ReadOnlyHalState state = new ReadOnlyHalState();
    String baseline = state.snapshot().toString();
    if (Looper.myLooper() == null) Looper.prepare();
    Class<?> activity = Class.forName("android.app.ActivityThread");
    Object thread = activity.getMethod("systemMain").invoke(null);
    Context context = (Context) activity.getMethod("getSystemContext").invoke(thread);
    HalPeriodExperiment[] owner = {null, null};
    JSONObject result =
        new JSONObject().put("playbackCreated", capture).put("networkStarted", false);
    try (var policy =
        new RegisteredPolicy(
            context,
            new CaptureScope(false, 0, -1, true, false),
            address -> {
              owner[0] = new HalPeriodExperiment(address, false);
              if (mixer) owner[1] = new HalPeriodExperiment(address, true);
            })) {
      result.put("active", state.snapshot());
      if (capture)
        result.put(
            "capture",
            HalPortProbe.periodCapture(
                policy.address,
                java.util.UUID.randomUUID(),
                () -> {
                  if (owner[1] != null) owner[1].close();
                  owner[0].close();
                }));
      else {
        if (owner[1] != null) owner[1].close();
        owner[0].close();
      }
      Process dump = new ProcessBuilder("dumpsys", "media.audio_flinger").start();
      byte[] text = dump.getInputStream().readNBytes(1048577);
      if (dump.waitFor(2, TimeUnit.SECONDS) && text.length <= 1048576) {
        int io = OutputPeriodProbe.ownedOutput(state.snapshot(), policy.address);
        String section = "";
        for (String part :
            new String(text, StandardCharsets.UTF_8).replace("\r", "").split("Output thread "))
          if (part.contains("  I/O handle: " + io + "\n")) section = part;
        StringBuilder fields = new StringBuilder();
        for (String line : section.split("\n"))
          if (!line.trim().startsWith("-")
              && (line.contains("frame count:")
                  || line.contains("Sample rate:")
                  || line.contains("I/O handle:")
                  || line.contains("FastMixer thread"))) fields.append(line.trim()).append('\n');
        result.put("audioFlinger", fields.toString());
      } else dump.destroyForcibly();
    } catch (Exception error) {
      result.put("failure", error.getClass().getSimpleName() + ": " + error.getMessage());
    } finally {
      if (owner[1] != null) {
        owner[1].close();
        result
            .put("mixerTrace", owner[1].transcript.toString())
            .put("mixerApprovedCalls", owner[1].accepted);
      }
      if (owner[0] != null) {
        owner[0].close();
        result
            .put("trace", owner[0].transcript.toString())
            .put("approvedCalls", owner[0].accepted)
            .put("traceFailure", owner[0].failure == null ? JSONObject.NULL : owner[0].failure);
      }
      boolean restored = false;
      for (int i = 0; i < 60; i++) {
        try {
          restored = baseline.equals(state.snapshot().toString());
        } catch (Exception unavailable) {
          result.put("moduleUnavailable", true);
          break;
        }
        if (restored) break;
        Thread.sleep(50);
      }
      result.put("resourcesRestoredExactly", restored);
      if (!restored)
        result.put("baseline", new JSONObject(baseline)).put("after", state.snapshot());
    }
    return result;
  }

  @Override
  public synchronized void close() throws Exception {
    if (closed) return;
    closed = true;
    stopping = true;
    process.getOutputStream().close();
    if (!process.waitFor(3, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(2, TimeUnit.SECONDS);
    }
    control.join(1000);
    boolean clean =
        process.exitValue() == 0
            && transcript
                .toString()
                .matches("(?s).*DONE hits=[0-9]+ changed=[0-9]+ clean=1 ok=1.*");
    Files.deleteIfExists(directory.resolve("probe"));
    Files.deleteIfExists(directory);
    if (!clean) throw new IllegalStateException("Period preparation cleanup unconfirmed");
    cleanupConfirmed = true;
  }
}
