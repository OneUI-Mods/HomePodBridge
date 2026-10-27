package dev.ryuya.homepodbridge.halprobe;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native inspection runs in a child; the Java owner retains all original stream handles/FDs. */
public final class HalQueueMapping {
  private static java.io.OutputStream forwardedOutput;
  private static final ThreadLocal<PreparedWorker> preparedWorker = new ThreadLocal<>();

  /** Preloads only the isolated JVM/native library. No HAL FDs are sent before verification. */
  static final class PreparedWorker implements AutoCloseable {
    final String nonce;
    final ProbeScenario scenario;
    final LocalServerSocket server;
    private Process child;
    private boolean claimed;

    PreparedWorker(String nonce, ProbeScenario scenario) throws Exception {
      if (preparedWorker.get() != null
          || !scenario.live()
          || !java.util.UUID.fromString(nonce).toString().equals(nonce))
        throw new SecurityException();
      this.nonce = nonce;
      this.scenario = scenario;
      String apk = System.getenv("CLASSPATH");
      if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk"))
        throw new SecurityException();
      server = new LocalServerSocket("hpb-map-" + nonce);
      try {
        child =
            new ProcessBuilder(
                    "app_process",
                    "/system/bin",
                    HalQueueMapping.class.getName(),
                    "hpb-map-" + nonce,
                    nonce,
                    Integer.toString(android.os.Process.myPid()),
                    scenario.workerMode)
                .redirectError(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")))
                .start();
        preparedWorker.set(this);
      } catch (Exception failed) {
        server.close();
        throw failed;
      }
    }

    Process claim(String nonce, ProbeScenario scenario, FileDescriptor directSocket) {
      if (claimed || !this.nonce.equals(nonce) || this.scenario != scenario || directSocket == null)
        throw new SecurityException("Prepared worker mismatch");
      claimed = true;
      return child;
    }

    @Override
    public void close() {
      if (preparedWorker.get() == this) preparedWorker.remove();
      if (child != null && child.isAlive()) child.destroyForcibly();
      try {
        server.close();
      } catch (java.io.IOException ignored) {
      }
    }
  }

  private static native long[] inspect(int[] fds, long[] grants, int quantum, int flags);

  private static native long[] queryStandby(
      int[] commandFds, long[] commandGrants, int[] replyFds, long[] replyGrants);

  private static native boolean exitAfterStatus(
      int[] commandFds, long[] commandGrants, int[] replyFds, long[] replyGrants);

  private static native long[] zeroTransfer(
      int[][] fds, long[][] grants, int frames, boolean exitAfterInput, boolean outputOnly);

  private static native long[] policyInputRead(
      int[][] fds,
      long[][] grants,
      boolean forwardZero,
      boolean live,
      boolean sanitize,
      int frames,
      boolean eventWait,
      int directOutput,
      int ownerControl);

  static JSONObject inspectInChild(
      List<FrozenHalParcel.Stream> streams, String nonce, ProbeScenario scenario) throws Exception {
    return inspectInChild(streams, nonce, scenario, null, () -> false);
  }

  static JSONObject inspectInChild(
      List<FrozenHalParcel.Stream> streams,
      String nonce,
      ProbeScenario scenario,
      java.io.OutputStream sink,
      java.util.function.BooleanSupplier stopped)
      throws Exception {
    return inspectInChild(streams, nonce, scenario, sink, stopped, null);
  }

  static JSONObject inspectInChild(
      List<FrozenHalParcel.Stream> streams,
      String nonce,
      ProbeScenario scenario,
      java.io.OutputStream sink,
      java.util.function.BooleanSupplier stopped,
      FileDescriptor directSocket)
      throws Exception {
    if (directSocket != null && !scenario.live()) throw new SecurityException();
    String apk = System.getenv("CLASSPATH");
    if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk"))
      throw new SecurityException();
    String name = "hpb-map-" + nonce;
    Process child = null;
    Thread forwarder = null;
    Thread stopper = null;
    PreparedWorker prepared = preparedWorker.get();
    try (var server = prepared == null ? new LocalServerSocket(name) : prepared.server) {
      var command =
          new ProcessBuilder(
              "app_process",
              "/system/bin",
              HalQueueMapping.class.getName(),
              name,
              nonce,
              Integer.toString(android.os.Process.myPid()),
              scenario.workerMode);
      if (scenario == ProbeScenario.POLICY_FORWARD || scenario.live())
        command.redirectError(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")));
      if (directSocket != null)
        command.redirectOutput(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")));
      child = prepared == null ? command.start() : prepared.claim(nonce, scenario, directSocket);
      if (directSocket == null && (scenario == ProbeScenario.POLICY_FORWARD || scenario.live())) {
        Process producer = child;
        // Keep stdout open until the resource owner's final cleanup and exit.
        forwardedOutput = sink != null ? sink : new java.io.FileOutputStream(FileDescriptor.out);
        forwarder =
            new Thread(
                () -> {
                  try (var input = producer.getInputStream()) {
                    byte[] block = new byte[4096];
                    int count;
                    while ((count = input.read(block)) != -1)
                      forwardedOutput.write(block, 0, count);
                    forwardedOutput.flush();
                  } catch (java.io.IOException disconnected) {
                    // Closing the reader makes the bounded native writer exit; the Java owner
                    // cleans up.
                  }
                },
                "HomePod-zero-forward");
        forwarder.setDaemon(true);
        forwarder.start();
      }
      var pending = new android.system.StructPollfd();
      pending.fd = server.getFileDescriptor();
      pending.events = (short) android.system.OsConstants.POLLIN;
      long connectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
      while (System.nanoTime() < connectDeadline && child.isAlive()) {
        android.system.Os.poll(new android.system.StructPollfd[] {pending}, 100);
        if ((pending.revents & android.system.OsConstants.POLLIN) != 0) break;
      }
      if ((pending.revents & android.system.OsConstants.POLLIN) == 0) {
        String reason =
            child.isAlive()
                ? "connection timeout"
                : new String(
                    child.getErrorStream().readNBytes(1024),
                    java.nio.charset.StandardCharsets.UTF_8);
        throw new java.io.IOException("Mapping worker did not connect: " + reason);
      }
      try (var socket = server.accept()) {
        socket.setSoTimeout(
            scenario.live() ? 0 : scenario == ProbeScenario.POLICY_FORWARD ? 15000 : 3000);
        var peer = socket.getPeerCredentials();
        String peerStat =
            new String(
                java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of("/proc", Integer.toString(peer.getPid()), "stat")),
                java.nio.charset.StandardCharsets.UTF_8);
        var identity =
            WorkerIdentity.authenticate(
                peer.getPid(),
                peer.getUid(),
                android.os.Process.myPid(),
                peerStat,
                procString(peer.getPid(), "status"),
                procString(peer.getPid(), "cmdline"),
                nonce,
                scenario);
        if (scenario.live()) {
          Process worker = child;
          stopper =
              new Thread(
                  () -> {
                    try {
                      while (worker.isAlive() && !stopped.getAsBoolean()) Thread.sleep(100);
                      if (worker.isAlive() && !worker.waitFor(2, TimeUnit.SECONDS))
                        killWorker(identity);
                    } catch (Exception ignored) {
                    }
                  },
                  "HomePod-capture-client-death");
          stopper.setDaemon(true);
          stopper.start();
        }
        var descriptors = new ArrayList<FileDescriptor>();
        var bytes = new ByteArrayOutputStream();
        var metadata = new DataOutputStream(bytes);
        metadata.writeUTF(nonce);
        metadata.writeInt(streams.size() * 3);
        for (var stream : streams)
          for (var queue : List.of(stream.command, stream.reply, stream.data)) {
            metadata.writeInt(queue.quantum);
            metadata.writeInt(queue.flags);
            metadata.writeInt(queue.fds.length);
            metadata.writeInt(queue.grants.length);
            for (var fd : queue.fds) descriptors.add(fd.getFileDescriptor());
            for (var grant : queue.grants) {
              metadata.writeInt(grant.fd);
              metadata.writeLong(grant.offset);
              metadata.writeLong(grant.extent);
            }
          }
        metadata.writeBoolean(directSocket != null);
        if (directSocket != null) descriptors.add(directSocket);
        if (descriptors.size() > 48 || bytes.size() > 8192) throw new SecurityException();
        socket.setFileDescriptorsForSend(descriptors.toArray(new FileDescriptor[0]));
        var output = new DataOutputStream(socket.getOutputStream());
        output.writeByte(0x51);
        output.flush();
        socket.setFileDescriptorsForSend(null);
        output.writeInt(bytes.size());
        output.write(bytes.toByteArray());
        output.flush();
        String result;
        long readStarted = System.nanoTime();
        try {
          result = new DataInputStream(socket.getInputStream()).readUTF();
        } catch (java.io.IOException unavailable) {
          if (scenario == ProbeScenario.POLICY_FORWARD || scenario.live())
            throw new java.io.IOException("Zero forwarding worker unavailable", unavailable);
          long readWaitNanos = System.nanoTime() - readStarted;
          var outcome = WorkerTermination.collect(child, readWaitNanos, () -> killWorker(identity));
          if (scenario.acceptsFailure(
              outcome.exitCode(), outcome.timedOut(), outcome.forced(), outcome.diagnostics()))
            return new JSONObject()
                .put("faultObserved", true)
                .put("fault", scenario.workerMode)
                .put("workerExit", outcome.exitCode())
                .put("forcedTermination", outcome.forced())
                .put("timedOut", outcome.timedOut())
                .put("readWaitMs", readWaitNanos / 1000000)
                .put("started", scenario.zeroFrames > 0);
          throw new java.io.IOException(
              "Mapping child failed: " + outcome.diagnostics(), unavailable);
        }
        if (result.length() > 2048) throw new SecurityException();
        if (!child.waitFor(2, TimeUnit.SECONDS) || child.exitValue() != 0)
          throw new java.io.IOException("Mapping worker failed");
        return new JSONObject(result);
      }
    } finally {
      if (child != null) child.destroyForcibly();
      if (stopper != null) stopper.interrupt();
      if (forwarder != null) forwarder.join(500);
    }
  }

  private static String procString(int pid, String name) throws Exception {
    return new String(
        java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("/proc", Integer.toString(pid), name)),
        java.nio.charset.StandardCharsets.UTF_8);
  }

  private static void killWorker(WorkerIdentity identity) throws Exception {
    try {
      if (!identity.matches(
          procString(identity.pid(), "stat"),
          procString(identity.pid(), "status"),
          procString(identity.pid(), "cmdline")))
        throw new SecurityException("Worker identity changed; signal refused");
    } catch (java.nio.file.NoSuchFileException exited) {
      return;
    }
    android.os.Process.sendSignal(identity.pid(), android.system.OsConstants.SIGKILL);
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4
        || android.os.Process.myUid() != 0
        || !args[0].equals("hpb-map-" + args[1])
        || !java.util.UUID.fromString(args[1]).toString().equals(args[1])
        || !args[2].matches("[1-9][0-9]{0,6}")
        || ProbeScenario.forWorker(args[3]) == null) throw new SecurityException();
    ProbeScenario scenario = ProbeScenario.forWorker(args[3]);
    if (scenario == ProbeScenario.POLICY_FORWARD || scenario.live()) System.setOut(System.err);
    String apk = System.getenv("CLASSPATH");
    if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk"))
      throw new SecurityException();
    System.load(apk + "!/lib/arm64-v8a/libc++_shared.so");
    System.load(apk + "!/lib/arm64-v8a/libhomepod_fmq_probe.so");
    System.out.println("MAPPING_LIBRARY_READY");
    FileDescriptor[] incoming = null;
    var owned = new ArrayList<ParcelFileDescriptor>();
    try (var socket = new LocalSocket()) {
      socket.connect(new LocalSocketAddress(args[0], LocalSocketAddress.Namespace.ABSTRACT));
      socket.setSoTimeout(scenario.live() ? 10000 : 3000);
      var peer = socket.getPeerCredentials();
      if (peer.getUid() != 0 || peer.getPid() != Integer.parseInt(args[2]))
        throw new SecurityException();
      var input = new DataInputStream(socket.getInputStream());
      if (input.readUnsignedByte() != 0x51) throw new SecurityException();
      incoming = socket.getAncillaryFileDescriptors();
      System.out.println("MAPPING_FDS=" + (incoming == null ? -1 : incoming.length));
      if (incoming == null || incoming.length == 0 || incoming.length > 48)
        throw new SecurityException();
      int length = input.readInt();
      if (length <= 0 || length > 8192) throw new SecurityException();
      byte[] data = new byte[length];
      input.readFully(data);
      var metadata = new DataInputStream(new ByteArrayInputStream(data));
      int queueCount = scenario.policyInput() ? 3 : 6;
      if (!metadata.readUTF().equals(args[1]) || metadata.readInt() != queueCount)
        throw new SecurityException();
      if (scenario == ProbeScenario.EXIT_FDS) {
        System.out.println("FAULT_CHECKPOINT=after-fds");
        System.out.flush();
        Runtime.getRuntime().halt(73);
      }
      JSONArray reports = new JSONArray();
      var queueFds = new ArrayList<int[]>();
      var queueGrants = new ArrayList<long[]>();
      int next = 0;
      for (int i = 0; i < queueCount; i++) {
        int quantum = metadata.readInt(),
            flags = metadata.readInt(),
            fdCount = metadata.readInt(),
            grantCount = metadata.readInt();
        if (quantum != new int[] {8, 56, 1}[i % 3]
            || flags != 1
            || fdCount < 1
            || fdCount > 8
            || next + fdCount > incoming.length
            || (grantCount != 3 && grantCount != 4)) throw new SecurityException();
        int[] fds = new int[fdCount];
        for (int f = 0; f < fdCount; f++) {
          var copy = ParcelFileDescriptor.dup(incoming[next++]);
          owned.add(copy);
          fds[f] = copy.getFd();
        }
        long[] grants = new long[grantCount * 3];
        for (int g = 0; g < grantCount; g++) {
          grants[g * 3] = metadata.readInt();
          grants[g * 3 + 1] = metadata.readLong();
          grants[g * 3 + 2] = metadata.readLong();
        }
        long[] result = inspect(fds, grants, quantum, flags);
        queueFds.add(fds);
        queueGrants.add(grants);
        System.out.println("MAPPING_NATIVE=" + java.util.Arrays.toString(result));
        if (result == null || result[0] < 0) {
          for (var fd : owned) {
            var stat = android.system.Os.fstat(fd.getFileDescriptor());
            System.out.println("MAPPING_FSTAT size=" + stat.st_size + " mode=" + stat.st_mode);
          }
          System.out.println("MAPPING_GRANTS=" + java.util.Arrays.toString(grants));
        }
        System.out.println("MAPPING_QUEUE=" + i + " accepted=" + (result != null));
        if (result == null || result.length != 3 || result[0] < 0)
          throw new SecurityException("Queue mapping rejected");
        reports.put(
            new JSONObject()
                .put("quantum", quantum)
                .put("capacityBytes", result[0])
                .put("readBytes", result[1])
                .put("writtenBytes", result[2]));
      }
      boolean direct = metadata.readBoolean();
      int directOutput = -1, ownerControl = -1;
      if (direct) {
        if (!scenario.live() || next + 1 != incoming.length) throw new SecurityException();
        var copy = ParcelFileDescriptor.dup(incoming[next++]);
        owned.add(copy);
        directOutput = copy.getFd();
        var control = ParcelFileDescriptor.dup(socket.getFileDescriptor());
        owned.add(control);
        ownerControl = control.getFd();
      }
      if (next != incoming.length || metadata.available() != 0) throw new SecurityException();
      if (scenario.policyInput()) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        long[] result =
            policyInputRead(
                queueFds.toArray(new int[0][]),
                queueGrants.toArray(new long[0][]),
                scenario == ProbeScenario.POLICY_FORWARD,
                scenario.live(),
                scenario == ProbeScenario.LIVE_ZERO,
                scenario.live() || scenario == ProbeScenario.POLICY_HALF ? 22 : 44,
                scenario != ProbeScenario.POLICY_POLL,
                directOutput,
                ownerControl);
        if (result == null || result.length != (scenario.live() ? 13 : 16))
          throw new SecurityException("Input read unavailable");
        JSONObject report =
            new JSONObject()
                .put("mappedQueues", 3)
                .put(
                    "framesPerBurst",
                    scenario.live() || scenario == ProbeScenario.POLICY_HALF ? 22 : 44)
                .put("passed", result[0] == 1)
                .put("capturedFrames", result[1])
                .put("cycles", result[2])
                .put("elapsedUs", result[3])
                .put(
                    "effectiveFramesPerSecond", result[3] > 0 ? result[1] * 1000000 / result[3] : 0)
                .put("availabilityGated", true)
                .put("maximumInputCommandUs", result[4])
                .put("maximumPendingFrames", result[5])
                .put("minimumPendingFrames", result[6])
                .put("meanPendingFrames", result[8])
                .put("sentFrames", scenario.live() ? result[9] : result[1])
                .put("idleFrames", scenario.live() ? result[10] : 0)
                .put("statusQueries", result[scenario.live() ? 11 : 9])
                .put("meanInputCommandUs", result[scenario.live() ? 12 : 10])
                .put("eventWait", scenario != ProbeScenario.POLICY_POLL)
                .put("directSocket", direct)
                .put("errorCode", result[7]);
        if (!scenario.live())
          report
              .put("producerEvents", result[11])
              .put("producerStepMedianFrames", result[12])
              .put("producerStepP95Frames", result[13])
              .put("producerIntervalMedianUs", result[14])
              .put("producerIntervalP95Us", result[15]);
        new DataOutputStream(socket.getOutputStream()).writeUTF(report.toString());
        return;
      }
      if (scenario == ProbeScenario.EXIT_MAP) {
        System.out.println("FAULT_CHECKPOINT=after-map");
        System.out.flush();
        Runtime.getRuntime().halt(73);
      }
      if (scenario == ProbeScenario.HANG) {
        System.out.println("FAULT_CHECKPOINT=hang");
        System.out.flush();
        Thread.sleep(10000);
        throw new SecurityException("Hang fault was not terminated");
      }
      if (scenario == ProbeScenario.EXIT_STATUS) {
        System.out.println("FAULT_CHECKPOINT=after-status");
        System.out.flush();
        exitAfterStatus(queueFds.get(0), queueGrants.get(0), queueFds.get(1), queueGrants.get(1));
        throw new SecurityException("Status dispatch fault failed");
      }
      if (scenario.zeroFrames > 0) {
        try {
          android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        } catch (RuntimeException unavailable) {
        }
        if (scenario == ProbeScenario.ZERO_INPUT_EXIT) {
          System.out.println("FAULT_CHECKPOINT=zero-input-exit");
          System.out.flush();
        }
        long[] flow =
            zeroTransfer(
                queueFds.toArray(new int[0][]),
                queueGrants.toArray(new long[0][]),
                scenario.zeroFrames,
                scenario == ProbeScenario.ZERO_INPUT_EXIT,
                scenario == ProbeScenario.ZERO_OUTPUT_BLOCK);
        if (flow == null || flow.length != 13)
          throw new SecurityException("Zero transfer unavailable");
        var transfer =
            new JSONObject()
                .put("passed", flow[0] == 1)
                .put("framesPerBurst", flow[1])
                .put("cycles", flow[2])
                .put("sentFrames", flow[3])
                .put("capturedFrames", flow[4])
                .put("elapsedUs", flow[5])
                .put("maximumOutputCommandUs", flow[6])
                .put("maximumInputCommandUs", flow[7])
                .put("maximumPendingFrames", flow[8])
                .put("errorCode", flow[9])
                .put("startFlags", flow[10])
                .put("inputObservableFrames", flow[11])
                .put("expectedInputFrames", flow[12]);
        transfer.put(
            "expectedBackpressure",
            scenario == ProbeScenario.ZERO_OUTPUT_BLOCK && flow[0] == 1 && flow[9] == 5);
        new DataOutputStream(socket.getOutputStream())
            .writeUTF(
                new JSONObject()
                    .put("mappedQueues", 6)
                    .put("started", flow[10] != 0)
                    .put("zeroTransfer", transfer)
                    .toString());
        return;
      }
      var control = new JSONArray();
      for (int base : new int[] {0, 3}) {
        long[] result =
            queryStandby(
                queueFds.get(base),
                queueGrants.get(base),
                queueFds.get(base + 1),
                queueGrants.get(base + 1));
        if (result == null || result.length != 4)
          throw new SecurityException("Standby status command failed");
        control.put(
            new JSONObject()
                .put("input", base == 3)
                .put("roundTripUs", result[0])
                .put("state", result[1])
                .put("audioBytes", result[2])
                .put("xrunFrames", result[3]));
      }
      new DataOutputStream(socket.getOutputStream())
          .writeUTF(
              new JSONObject()
                  .put("mappedQueues", 6)
                  .put("queues", reports)
                  .put("started", false)
                  .put("statusCommands", control)
                  .toString());
    } finally {
      for (var fd : owned)
        try {
          fd.close();
        } catch (Exception ignored) {
        }
      if (incoming != null)
        for (var fd : incoming)
          try {
            android.system.Os.close(fd);
          } catch (Exception ignored) {
          }
    }
  }

  private HalQueueMapping() {}
}
