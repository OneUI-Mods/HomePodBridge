package dev.ryuya.homepodbridge.halprobe;

import android.os.Parcel;
import android.os.Parcelable;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded virtual-route probes; data operations use only self-owned streams and fixed zeros. */
public final class HalPortProbe implements OwnedHalResources.Backend {
  private final ReadOnlyHalState state = new ReadOnlyHalState();
  private final String epoch = epoch();
  private final String address;
  private final OwnedHalResources.Tag tag;
  private final int rate;
  private final boolean fast;
  private StreamOpenLedger ledger;
  private final List<OpenStream> streams = new java.util.concurrent.CopyOnWriteArrayList<>();
  private final java.util.concurrent.atomic.AtomicBoolean stopRequested =
      new java.util.concurrent.atomic.AtomicBoolean();

  private static final ThreadLocal<ProbeScenario> diagnosticScenario = new ThreadLocal<>();

  private java.util.function.BooleanSupplier externalStopped = () -> false;

  private boolean isStopping() {
    return stopRequested.get() || externalStopped.getAsBoolean();
  }

  private static final class OpenStream {
    final android.os.IBinder binder;
    final boolean input;
    final int config;
    volatile android.os.IBinder common;
    volatile FrozenHalParcel.Stream descriptor;
    volatile boolean closed;

    OpenStream(android.os.IBinder binder, boolean input, int config) {
      this.binder = binder;
      this.input = input;
      this.config = config;
    }
  }

  HalPortProbe(UUID nonce, int rate, boolean fast) throws Exception {
    this(nonce, rate, fast, null);
  }

  private HalPortProbe(UUID nonce, int rate, boolean fast, String registration) throws Exception {
    if (rate != 44100 && rate != 32000 && rate != 16000 && rate != 8000)
      throw new IllegalArgumentException();
    this.rate = rate;
    this.fast = fast;
    if (registration != null && !registration.matches("[0-9]+:ap:[0-9]+mixp:[0-9]+"))
      throw new SecurityException("Unexpected policy registration");
    address = registration == null ? "HomePodBridgeDirect:" + nonce : registration;
    int marker = (nonce.hashCode() & 0x07ffffff) | 0x40000000;
    tag =
        new OwnedHalResources.Tag(
            "HomePodBridgeDirect:" + nonce, 3, 4, marker, marker ^ 0x20000000);
  }

  /** Borrow a policy-owned input port, owning only the newly returned config and patch IDs. */
  interface Ready {
    void run() throws Exception;
  }

  static JSONObject policyCapture(String address, UUID nonce, int target, boolean forwardZero)
      throws Exception {
    return inputSession(
        address, nonce, target, forwardZero, false, true, false, null, () -> false, () -> {});
  }

  static JSONObject inputSession(
      String address,
      UUID nonce,
      int target,
      boolean forwardZero,
      boolean live,
      boolean zeroOnly,
      boolean pulse,
      java.io.OutputStream sink,
      java.util.function.BooleanSupplier stopped,
      Ready ready)
      throws Exception {
    return inputSession(
        address, nonce, target, forwardZero, live, zeroOnly, pulse, sink, stopped, ready, null);
  }

  static JSONObject inputSession(
      String address,
      UUID nonce,
      int target,
      boolean forwardZero,
      boolean live,
      boolean zeroOnly,
      boolean pulse,
      java.io.OutputStream sink,
      java.util.function.BooleanSupplier stopped,
      Ready ready,
      java.io.FileDescriptor directSocket)
      throws Exception {
    if (target != 2587
        && target != 928
        && target != 464
        && target != 800
        && target != 752
        && target != 768) throw new IllegalArgumentException();
    HalPortProbe probe = new HalPortProbe(nonce, 44100, false, address);
    probe.externalStopped = stopped;
    var baseline = probe.snapshot();
    int device = 0;
    for (var port : baseline.ports())
      if (port.type() == 11 && address.equals(port.address())) {
        if (device != 0) throw new SecurityException("Ambiguous policy input");
        device = port.id();
      }
    if (device == 0) throw new SecurityException("Policy input port missing");
    Path journal = Path.of("/data/local/tmp/homepod-policy-input-" + nonce + ".json");
    JSONObject document =
        probe
            .state
            .snapshot()
            .put("epoch", probe.epoch)
            .put("registeredAddress", address)
            .put("zeroOnly", zeroOnly)
            .put("streamLedger", new JSONArray());
    Files.write(
        journal,
        document.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);
    Files.setPosixFilePermissions(journal, PosixFilePermissions.fromString("rw-------"));
    probe.ledger =
        new StreamOpenLedger(
            entries -> {
              var values = new JSONArray();
              for (var entry : entries)
                values.put(
                    new JSONObject()
                        .put("config", entry.configId())
                        .put("input", entry.input())
                        .put("phase", entry.phase().name()));
              document.put("streamLedger", values);
              Path next = journal.resolveSibling(journal.getFileName() + ".next");
              Files.write(
                  next,
                  document.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.WRITE);
              Files.setPosixFilePermissions(next, PosixFilePermissions.fromString("rw-------"));
              try (var channel =
                  java.nio.channels.FileChannel.open(next, StandardOpenOption.WRITE)) {
                channel.force(true);
              }
              Files.move(
                  next,
                  journal,
                  java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                  java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            },
            List.of());
    var configs = new ArrayList<OwnedHalResources.Config>();
    OwnedHalResources.Patch patch = null;
    var report = new JSONObject();
    boolean tuneAttempted = false;
    var watchdog =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "HomePod-policy-input-close");
              t.setDaemon(true);
              return t;
            });
    var startupTimeout =
        watchdog.schedule(
            () -> {
              probe.stopRequested.set(true);
              probe.closeStreams();
            },
            live ? 20 : forwardZero ? 22 : 15,
            TimeUnit.SECONDS);
    if (forwardZero && !live) {
      Thread stdinWatcher =
          new Thread(
              () -> {
                try (var control = new java.io.FileInputStream(java.io.FileDescriptor.in)) {
                  control.read();
                } catch (java.io.IOException ignored) {
                } finally {
                  probe.stopRequested.set(true);
                  // The bounded output writer observes the closed/full client pipe. Keep the input
                  // alive until its owner restores the reserve, then closes it in the normal
                  // finally.
                }
              },
              "HomePod-zero-client-death");
      stdinWatcher.setDaemon(true);
      stdinWatcher.start();
    }
    try {
      int mix = probe.configure(4, true, true);
      configs.add(
          probe.snapshot().configs().stream().filter(c -> c.id() == mix).findFirst().orElseThrow());
      int deviceConfig = probe.configure(device, true, false);
      configs.add(
          probe.snapshot().configs().stream()
              .filter(c -> c.id() == deviceConfig)
              .findFirst()
              .orElseThrow());
      var connected = probe.patch(deviceConfig, mix);
      patch = new OwnedHalResources.Patch(connected.id, List.of(deviceConfig), List.of(mix));
      report.put("stream", probe.open(mix, true, connected.minimumFrames));
      report.put("targetFillFrames", target);
      if (target != 2587) {
        tuneAttempted = true;
        String tuning = changePolicyReserve(address, reserveOperation(target, false));
        report.put("tuning", tuning);
        if (!tuning.contains("Owned reserve changed: 2587 -> " + target + ";"))
          throw new IllegalStateException("Reserve change not confirmed");
      }
      ready.run();
      if (live) startupTimeout.cancel(false);
      try (var source = !live || zeroOnly ? new ZeroPlayback(pulse) : null) {
        report.put(
            "mapping",
            HalQueueMapping.inspectInChild(
                probe.streams.stream().map(stream -> stream.descriptor).toList(),
                nonce.toString(),
                live
                    ? (zeroOnly ? ProbeScenario.LIVE_ZERO : ProbeScenario.LIVE)
                    : forwardZero
                        ? ProbeScenario.POLICY_FORWARD
                        : diagnosticScenario.get() != null
                            ? diagnosticScenario.get()
                            : ProbeScenario.POLICY_INPUT,
                sink,
                stopped,
                directSocket));
        report.put("sourceWrittenFrames", source == null ? 0 : source.writtenFrames());
      }
    } catch (Exception failure) {
      report.put("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
    } finally {
      if (tuneAttempted) {
        try {
          report.put("restore", changePolicyReserve(address, reserveOperation(target, true)));
        } catch (Exception failure) {
          report.put("restoreFailure", failure.getClass().getSimpleName());
        }
      }
      probe.closeStreams();
      watchdog.shutdownNow();
      if (probe.streamsClosed()) {
        if (patch != null && probe.snapshot().patches().contains(patch))
          probe.removePatch(patch.id());
        for (int i = configs.size() - 1; i >= 0; i--) {
          var config = configs.get(i);
          var current = probe.snapshot();
          if (current.configs().contains(config)
              && current.patches().stream()
                  .noneMatch(
                      p -> p.sources().contains(config.id()) || p.sinks().contains(config.id())))
            probe.removeConfig(config.id());
        }
      }
      report
          .put("streamsClosed", probe.streamsClosed())
          .put("openReplyUncertain", probe.ledger.replyUncertain())
          .put("resourcesRestoredExactly", probe.snapshot().equals(baseline));
      var after = probe.snapshot();
      boolean removed =
          configs.stream().noneMatch(c -> after.configs().stream().anyMatch(x -> x.id() == c.id()));
      if (patch != null)
        for (var existing : after.patches()) if (existing.id() == patch.id()) removed = false;
      report.put(
          "ownedInputReleased", removed && probe.streamsClosed() && !probe.ledger.replyUncertain());
    }
    return report;
  }

  private static String reserveOperation(int target, boolean restore) {
    String operation =
        switch (target) {
          case 928 -> "brief";
          case 464 -> "minimum";
          case 800 -> "direct";
          case 752 -> "direct-752";
          case 768 -> "direct-768";
          default -> throw new IllegalArgumentException();
        };
    return restore ? "restore-" + operation : operation;
  }

  private static String changePolicyReserve(String address, String operation) throws Exception {
    Process child =
        new ProcessBuilder(
                "timeout",
                "-k",
                "1",
                "5",
                "app_process",
                "/system/bin",
                "dev.ryuya.homepodbridge.HalRootProbe",
                address,
                operation)
            .start();
    try {
      if (!child.waitFor(7, TimeUnit.SECONDS))
        throw new java.io.IOException("Reserve helper timed out");
      String result =
          new String(
              child.getInputStream().readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8);
      if (child.exitValue() != 0) throw new java.io.IOException("Reserve helper failed");
      return result.trim();
    } finally {
      if (child.isAlive()) child.destroyForcibly();
    }
  }

  static String epoch() throws Exception {
    Process query =
        new ProcessBuilder("dumpsys", "--pid", "android.hardware.audio.core.IModule/r_submix")
            .start();
    String pid =
        new String(
                query.getInputStream().readNBytes(128), java.nio.charset.StandardCharsets.US_ASCII)
            .trim();
    if (!query.waitFor(2, TimeUnit.SECONDS) || !pid.matches("[0-9]{1,7}"))
      throw new IllegalStateException("Module PID unavailable");
    Path proc = Path.of("/proc", pid);
    if (!readString(proc.resolve("cmdline"))
            .split("\u0000")[0]
            .equals("/vendor/bin/hw/audiohalservice.qti")
        || !readString(proc.resolve("status"))
            .matches("(?s).*\\nUid:\\s+1041\\s+1041\\s+1041\\s+1041\\s*\\n.*"))
      throw new SecurityException("Unexpected module process");
    String stat = readString(proc.resolve("stat"));
    String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split("\\s+");
    if (fields.length < 20 || !fields[19].matches("[0-9]+"))
      throw new IllegalStateException("Module start time unavailable");
    return pid + ":" + fields[19];
  }

  static OwnedHalResources.Snapshot decode(JSONObject value, String epoch) throws Exception {
    var ports = new ArrayList<OwnedHalResources.Port>();
    var configs = new ArrayList<OwnedHalResources.Config>();
    var patches = new ArrayList<OwnedHalResources.Patch>();
    JSONArray array = value.getJSONArray("ports");
    for (int i = 0; i < array.length(); i++) {
      var p = array.getJSONObject(i);
      ports.add(
          new OwnedHalResources.Port(
              p.getInt("id"), p.optInt("deviceType", 0), p.optString("address", "")));
    }
    array = value.getJSONArray("configs");
    for (int i = 0; i < array.length(); i++) {
      var c = array.getJSONObject(i);
      configs.add(
          new OwnedHalResources.Config(c.getInt("id"), c.getInt("portId"), c.optInt("handle", 0)));
    }
    array = value.getJSONArray("patches");
    for (int i = 0; i < array.length(); i++) {
      var p = array.getJSONObject(i);
      patches.add(
          new OwnedHalResources.Patch(
              p.getInt("id"),
              integers(p.getJSONArray("sources")),
              integers(p.getJSONArray("sinks"))));
    }
    return new OwnedHalResources.Snapshot(epoch, ports, configs, patches);
  }

  static List<Integer> integers(JSONArray array) throws Exception {
    var result = new ArrayList<Integer>();
    for (int i = 0; i < array.length(); i++) result.add(array.getInt(i));
    return result;
  }

  public OwnedHalResources.Snapshot snapshot() throws Exception {
    if (!state.module.isBinderAlive()) {
      String current = epoch();
      if (epoch.equals(current))
        throw new java.io.IOException("Binder unavailable but original module still exists");
      return new OwnedHalResources.Snapshot(current, List.of(), List.of(), List.of());
    }
    return decode(state.snapshot(), epoch);
  }

  public boolean streamsClosed() {
    return ledger != null && ledger.allClosed() && streams.stream().allMatch(s -> s.closed);
  }

  private static Parcel call(android.os.IBinder binder, String descriptor, int method)
      throws Exception {
    if (!descriptor.equals(binder.getInterfaceDescriptor()))
      throw new SecurityException("Unexpected stream interface");
    Parcel in = Parcel.obtain(), out = Parcel.obtain();
    try {
      in.writeInterfaceToken(descriptor);
      if (!binder.transact(method, in, out, 0))
        throw new IllegalStateException("Stream method unavailable");
      out.readException();
      return out;
    } catch (Exception failure) {
      out.recycle();
      throw failure;
    } finally {
      in.recycle();
    }
  }

  private static void common(OpenStream stream) throws Exception {
    if (stream.common != null) return;
    String type = "android.hardware.audio.core." + (stream.input ? "IStreamIn" : "IStreamOut");
    Parcel reply = call(stream.binder, type, 1);
    try {
      stream.common = reply.readStrongBinder();
      if (stream.common == null
          || !"android.hardware.audio.core.IStreamCommon"
              .equals(stream.common.getInterfaceDescriptor()))
        throw new SecurityException("Unexpected common interface");
    } finally {
      reply.recycle();
    }
  }

  synchronized void closeStreams() {
    for (int i = streams.size() - 1; i >= 0; i--) {
      OpenStream s = streams.get(i);
      if (s.closed) continue;
      try {
        common(s);
        call(s.common, "android.hardware.audio.core.IStreamCommon", 2).recycle();
        call(s.common, "android.hardware.audio.core.IStreamCommon", 1).recycle();
        ledger.closed(s.config);
        s.closed = true;
      } catch (Exception ignored) {
        // Binder failure does not prove that the module or stream has gone away.
      } finally {
        if (s.descriptor != null) s.descriptor.close();
      }
    }
  }

  JSONObject open(int config, boolean input, int frames) throws Exception {
    if (isStopping()) throw new IllegalStateException("Probe stopping");
    if (frames <= 0 || frames > 262144) throw new IllegalArgumentException();
    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
    final OpenStream[] holder = {null};
    try {
      data.writeInterfaceToken(ReadOnlyHalState.DESCRIPTOR);
      FrozenHalParcel.writeOpenArgs(data, config, frames, input);
      ledger.begin(config, input);
      if (!state.module.transact(input ? 14 : 15, data, reply, 0))
        throw new IllegalStateException("Open unavailable");
      reply.readException();
      FrozenHalParcel.Stream desc =
          FrozenHalParcel.stream(
              reply,
              binder -> {
                OpenStream stream = new OpenStream(binder, input, config);
                streams.add(stream);
                holder[0] = stream;
                try {
                  ledger.received(config);
                } catch (Exception error) {
                  throw new IllegalStateException("Stream journal unavailable", error);
                }
                if (isStopping()) throw new IllegalStateException("Probe stopping");
                try {
                  common(stream);
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              });
      holder[0].descriptor = desc;
      return new JSONObject()
          .put("input", input)
          .put("frameBytes", desc.frameBytes)
          .put("bufferFrames", desc.bufferFrames)
          .put("commandQuantum", desc.command.quantum)
          .put("replyQuantum", desc.reply.quantum)
          .put("dataBytes", desc.data.grants[2].extent)
          .put("openedUnstarted", true);
    } finally {
      data.recycle();
      reply.recycle();
    }
  }

  private void remove(int transaction, int id) throws Exception {
    if (transaction != 7 && transaction != 19 && transaction != 20) throw new SecurityException();
    Parcel input = Parcel.obtain(), reply = Parcel.obtain();
    try {
      input.writeInterfaceToken(ReadOnlyHalState.DESCRIPTOR);
      input.writeInt(id);
      if (!state.module.transact(transaction, input, reply, 0))
        throw new IllegalStateException("Cleanup unavailable");
      reply.readException();
    } finally {
      input.recycle();
      reply.recycle();
    }
  }

  public void removePatch(int id) throws Exception {
    remove(19, id);
  }

  public void removeConfig(int id) throws Exception {
    remove(20, id);
  }

  public void disconnectPort(int id) throws Exception {
    remove(7, id);
  }

  private static void number(Object object, String field, int value) throws Exception {
    var f = object.getClass().getField(field);
    if (f.getType() == byte.class) f.setByte(object, (byte) value);
    else if (f.getType() == short.class) f.setShort(object, (short) value);
    else f.setInt(object, value);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  int configure(int portId, boolean input, boolean mix) throws Exception {
    if (isStopping()) throw new IllegalStateException("Probe stopping");
    Object port = null;
    for (Object candidate : state.objects(11, "android.media.audio.common.AudioPort"))
      if (ReadOnlyHalState.integer(candidate, "id") == portId) port = candidate;
    if (port == null) throw new IllegalStateException("Owned port unavailable");
    Object requested =
        Class.forName("android.media.audio.common.AudioPortConfig").getConstructor().newInstance();
    number(requested, "portId", portId);
    var sampleField = requested.getClass().getField("sampleRate");
    Object sample = sampleField.getType().getConstructor().newInstance();
    number(sample, "value", rate);
    sampleField.set(requested, sample);
    var maskField = requested.getClass().getField("channelMask");
    maskField.set(
        requested, maskField.getType().getMethod("layoutMask", int.class).invoke(null, 3));
    var formatField = requested.getClass().getField("format");
    Object format = formatField.getType().getConstructor().newInstance();
    number(format, "type", 1);
    number(format, "pcm", 1);
    format.getClass().getField("encoding").set(format, "");
    formatField.set(requested, format);
    requested.getClass().getField("flags").set(requested, ReadOnlyHalState.field(port, "flags"));
    int requestedFlags = 0;
    if (fast && mix) {
      int bit =
          Class.forName(
                  "android.media.audio.common." + (input ? "AudioInputFlags" : "AudioOutputFlags"))
              .getField("FAST")
              .getInt(null);
      requestedFlags = 1 << bit;
      var field = requested.getClass().getField("flags");
      field.set(
          requested,
          field
              .getType()
              .getMethod(input ? "input" : "output", int.class)
              .invoke(null, requestedFlags));
    }
    Object ext = ReadOnlyHalState.field(port, "ext");
    if (mix) {
      if ((input && portId != tag.inputMix()) || (!input && portId != tag.outputMix()))
        throw new SecurityException();
      Object detail =
          ReadOnlyHalState.call(ext, "getMix").getClass().getConstructor().newInstance();
      number(detail, "handle", input ? tag.inputHandle() : tag.outputHandle());
      var use = detail.getClass().getField("usecase");
      use.set(
          detail,
          use.getType()
              .getMethod(input ? "source" : "stream", int.class)
              .invoke(null, input ? 8 : 3));
      ext = ext.getClass().getMethod("mix", detail.getClass()).invoke(null, detail);
    } else if (snapshot().ports().stream()
        .noneMatch(
            p -> p.id() == portId && address.equals(p.address()) && p.type() == (input ? 11 : 143)))
      throw new SecurityException("Not an owned virtual device");
    requested.getClass().getField("ext").set(requested, ext);
    var before = snapshot();
    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(ReadOnlyHalState.DESCRIPTOR);
      data.writeTypedObject((Parcelable) requested, 0);
      if (!state.module.transact(18, data, reply, 0))
        throw new IllegalStateException("Configure unavailable");
      reply.readException();
      boolean accepted = reply.readBoolean();
      Object suggested =
          reply.readTypedObject(
              (Parcelable.Creator) requested.getClass().getField("CREATOR").get(null));
      int id = suggested == null ? 0 : ReadOnlyHalState.integer(suggested, "id");
      if (!accepted
          || id <= 0
          || before.configs().stream().anyMatch(c -> c.id() == id)
          || ReadOnlyHalState.integer(suggested, "portId") != portId)
        throw new IllegalStateException("Port format rejected: " + suggested);
      var actual = ReadOnlyHalState.format(suggested);
      if (actual.getInt("rate") != rate
          || !actual.getBoolean("pcm16")
          || !actual.getBoolean("stereo"))
        throw new SecurityException("Unexpected configured format");
      if (mix
          && ((Number)
                      ReadOnlyHalState.call(
                          ReadOnlyHalState.field(suggested, "flags"),
                          input ? "getInput" : "getOutput"))
                  .intValue()
              != requestedFlags) throw new SecurityException("Requested flags not honored");
      if (mix
          && ReadOnlyHalState.integer(
                  ReadOnlyHalState.call(ReadOnlyHalState.field(suggested, "ext"), "getMix"),
                  "handle")
              != (input ? tag.inputHandle() : tag.outputHandle()))
        throw new SecurityException("Mix handle changed");
      return id;
    } finally {
      data.recycle();
      reply.recycle();
    }
  }

  FrozenHalParcel.Patch patch(int source, int sink) throws Exception {
    if (isStopping()) throw new IllegalStateException("Probe stopping");
    var before = snapshot();
    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(ReadOnlyHalState.DESCRIPTOR);
      FrozenHalParcel.writePatch(data, 0, new int[] {source}, new int[] {sink});
      if (!state.module.transact(17, data, reply, 0))
        throw new IllegalStateException("Patch unavailable");
      reply.readException();
      var value = FrozenHalParcel.patch(reply);
      if (value.id <= 0
          || before.patches().stream().anyMatch(p -> p.id() == value.id)
          || !java.util.Arrays.equals(value.sources, new int[] {source})
          || !java.util.Arrays.equals(value.sinks, new int[] {sink}))
        throw new SecurityException("Unexpected patch identity");
      return value;
    } finally {
      data.recycle();
      reply.recycle();
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  int createVirtualPort(int type) throws Exception {
    if (isStopping()) throw new IllegalStateException("Probe stopping");
    Object requested = null;
    for (Object port : state.objects(11, "android.media.audio.common.AudioPort")) {
      Object ext = ReadOnlyHalState.field(port, "ext");
      if (!ReadOnlyHalState.tag(ext, "device")) continue;
      Object device = ReadOnlyHalState.field(ReadOnlyHalState.call(ext, "getDevice"), "device");
      Object description = ReadOnlyHalState.field(device, "type"),
          address = ReadOnlyHalState.field(device, "address");
      if (ReadOnlyHalState.integer(description, "type") == type
          && "virtual".equals(ReadOnlyHalState.field(description, "connection"))
          && ReadOnlyHalState.tag(address, "id")
          && "".equals(ReadOnlyHalState.call(address, "getId"))) {
        if (requested != null) throw new IllegalStateException("Ambiguous template");
        requested = port;
      }
    }
    if (requested == null) throw new IllegalStateException("Virtual template unavailable");
    Object device =
        ReadOnlyHalState.field(
            ReadOnlyHalState.call(ReadOnlyHalState.field(requested, "ext"), "getDevice"), "device");
    Object value =
        Class.forName("android.media.audio.common.AudioDeviceAddress")
            .getMethod("id", String.class)
            .invoke(null, address);
    device.getClass().getField("address").set(device, value);
    Parcel input = Parcel.obtain(), reply = Parcel.obtain();
    try {
      input.writeInterfaceToken(ReadOnlyHalState.DESCRIPTOR);
      input.writeTypedObject((Parcelable) requested, 0);
      if (!state.module.transact(6, input, reply, 0))
        throw new IllegalStateException("Connect unavailable");
      reply.readException();
      Object returned =
          reply.readTypedObject(
              (Parcelable.Creator)
                  Class.forName("android.media.audio.common.AudioPort")
                      .getField("CREATOR")
                      .get(null));
      int id = ReadOnlyHalState.integer(returned, "id");
      var current = snapshot();
      if (current.ports().stream()
          .noneMatch(p -> p.id() == id && p.type() == type && address.equals(p.address())))
        throw new SecurityException("Created port identity changed");
      return id;
    } finally {
      input.recycle();
      reply.recycle();
    }
  }

  private static String readString(Path path) throws Exception {
    return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
  }

  static void rootPreflight() throws Exception {
    if (android.os.Process.myUid() != 0)
      throw new SecurityException("Root-owned transport required");
    String[][] pins = {
      {
        "/vendor/lib64/libaudioserviceexampleimpl.so",
        "ef1bad7d1ae1d66a6ac6b39a009fba43e00031bb9a3316e435965d4e91564d61"
      },
      {
        "/vendor/lib64/libnbaio_mono.so",
        "c363ecab034babaa344163bc4aaf67dfe42945055c433e0640ba555b86d159aa"
      }
    };
    for (var pin : pins) {
      Path file = Path.of(pin[0]);
      if (Files.size(file) > 4194304
          || !java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(Files.readAllBytes(file)))
              .equals(pin[1])) throw new SecurityException("Unsupported vendor build");
    }
    Process query =
        new ProcessBuilder("dumpsys", "android.hardware.audio.core.IModule/r_submix").start();
    byte[] output = query.getInputStream().readNBytes(8193);
    if (!query.waitFor(2, TimeUnit.SECONDS)
        || query.exitValue() != 0
        || output.length > 8192
        || !new String(output, java.nio.charset.StandardCharsets.UTF_8)
            .replace("\r", "")
            .trim()
            .equals("SubmixRoutes:\n <Empty>"))
      throw new SecurityException("Existing submix route; no allocation allowed");
  }

  public static void main(String[] args) {
    if (args.length > 0 && args[0].equals("policy-forward")) System.setOut(System.err);
    try {
      runMain(args);
    } catch (Throwable failure) {
      System.out.println(
          "{\"probeFailed\":true,\"errorType\":\"" + failure.getClass().getSimpleName() + "\"}");
    }
  }

  private static void runMain(String[] args) throws Exception {
    rootPreflight();
    if (args.length == 3 && args[0].equals("getter-period")) {
      if (!UUID.fromString(args[1]).toString().equals(args[1]) || !args[2].equals("44100"))
        throw new IllegalArgumentException();
      JSONObject result = HalPeriodExperiment.run();
      System.out.println(result);
      System.out.flush();
      if (result.optBoolean("resourcesRestoredExactly")) System.exit(0);
      return;
    }
    if (args.length == 3 && args[0].equals("output-period")) {
      if (!UUID.fromString(args[1]).toString().equals(args[1]) || !args[2].equals("44100"))
        throw new IllegalArgumentException();
      JSONObject result = OutputPeriodProbe.run();
      System.out.println(result);
      System.out.flush();
      if (result.optBoolean("resourcesRestoredExactly")) System.exit(0);
      return;
    }
    if (args.length == 3
        && (args[0].equals("policy-input")
            || args[0].equals("policy-route")
            || args[0].equals("policy-capture")
            || args[0].equals("policy-capture-fast")
            || args[0].equals("policy-capture-minimum")
            || args[0].equals("policy-capture-smaller")
            || args[0].equals("policy-capture-poll")
            || args[0].equals("policy-capture-half")
            || args[0].equals("policy-forward"))) {
      if (!UUID.fromString(args[1]).toString().equals(args[1]) || !args[2].equals("44100"))
        throw new IllegalArgumentException();
      if (args[0].equals("policy-capture-poll")) diagnosticScenario.set(ProbeScenario.POLICY_POLL);
      if (args[0].equals("policy-capture-half")) diagnosticScenario.set(ProbeScenario.POLICY_HALF);
      JSONObject report =
          PolicyInputProbe.run(
              !args[0].equals("policy-input"),
              UUID.fromString(args[1]),
              args[0].startsWith("policy-capture") || args[0].equals("policy-forward"),
              args[0].equals("policy-capture-fast")
                  ? 928
                  : args[0].equals("policy-capture-minimum")
                      ? 464
                      : java.util.Set.of(
                                  "policy-capture-smaller",
                                  "policy-forward",
                                  "policy-capture-poll",
                                  "policy-capture-half")
                              .contains(args[0])
                          ? 800
                          : 2587,
              args[0].equals("policy-forward"));
      System.out.println(report);
      System.out.flush();
      JSONObject direct = report.optJSONObject("directCapture");
      // ActivityThread's platform helpers can outlive main. Exit only after confirmed cleanup,
      // never while the stream owner might still hold a live or uncertain HAL allocation.
      if (report.optBoolean("resourcesRestoredExactly")
          && (direct == null
              || (direct.optBoolean("streamsClosed")
                  && !direct.optBoolean("openReplyUncertain")
                  && direct.optBoolean("resourcesRestoredExactly")))) System.exit(0);
      return;
    }
    if (args.length == 3 && args[0].equals("metrics")) {
      if (!UUID.fromString(args[1]).toString().equals(args[1]) || !args[2].equals("44100"))
        throw new IllegalArgumentException();
      String current = epoch();
      Path proc = Path.of("/proc", current.split(":")[0]);
      long fds, threads;
      try (var files = Files.list(proc.resolve("fd"))) {
        fds = files.count();
      }
      try (var tasks = Files.list(proc.resolve("task"))) {
        threads = tasks.count();
      }
      if (!current.equals(epoch()))
        throw new SecurityException("Module changed during observation");
      System.out.println(
          new JSONObject()
              .put("moduleEpoch", current)
              .put("openFdCount", fds)
              .put("threadCount", threads)
              .put("readOnly", true));
      return;
    }
    if (args.length == 3 && args[0].equals("stop")) {
      String nonce = UUID.fromString(args[1]).toString();
      if (!nonce.equals(args[1]) || !args[2].equals("44100")) throw new IllegalArgumentException();
      var matches = new ArrayList<Path>();
      try (var processes = Files.list(Path.of("/proc"))) {
        for (Path proc :
            processes.filter(p -> p.getFileName().toString().matches("[1-9][0-9]{0,6}")).toList()) {
          try {
            if (OwnedProbeProcess.matches(
                readString(proc.resolve("cmdline")), readString(proc.resolve("status")), nonce))
              matches.add(proc);
          } catch (Exception exited) {
            /* Unrelated process exited during inventory. */
          }
        }
      }
      if (matches.size() != 1)
        throw new SecurityException("Expected exactly one owned prototype process");
      Path proc = matches.get(0);
      String command = readString(proc.resolve("cmdline"));
      String identity = readString(proc.resolve("stat"));
      if (OwnedProbeProcess.matchesClosedCycle(command, nonce)) {
        Path journal = Path.of("/data/local/tmp/homepod-policy-input-" + nonce + ".json");
        if (Files.isSymbolicLink(journal)
            || Files.size(journal) > 65536
            || ((Number) Files.getAttribute(journal, "unix:uid", LinkOption.NOFOLLOW_LINKS))
                    .intValue()
                != 0) throw new SecurityException("Invalid closed capture journal");
        JSONObject saved = new JSONObject(readString(journal));
        var probe = new HalPortProbe(UUID.fromString(nonce), 44100, false);
        var current = probe.snapshot();
        if (!saved.getBoolean("zeroOnly")
            || !saved.getString("epoch").equals(probe.epoch)
            || current.ports().stream()
                .anyMatch(p -> p.address().equals(saved.optString("registeredAddress"))))
          throw new SecurityException("Capture policy still owned");
        JSONArray entries = saved.getJSONArray("streamLedger");
        if (entries.length() == 0) throw new SecurityException("Missing closed capture proof");
        for (int i = 0; i < entries.length(); i++) {
          JSONObject entry = entries.getJSONObject(i);
          if (!entry.getString("phase").equals("CLOSED")
              || current.configs().stream().anyMatch(c -> c.id() == entry.optInt("config")))
            throw new SecurityException("Capture input still owned");
        }
      }
      if (!OwnedProbeProcess.matches(
          readString(proc.resolve("cmdline")), readString(proc.resolve("status")), nonce))
        throw new SecurityException();
      String now = readString(proc.resolve("stat"));
      if (!identity
          .substring(identity.lastIndexOf(')') + 2)
          .split("\\s+")[19]
          .equals(now.substring(now.lastIndexOf(')') + 2).split("\\s+")[19]))
        throw new SecurityException("Helper identity changed");
      android.os.Process.sendSignal(Integer.parseInt(proc.getFileName().toString()), 15);
      System.out.println("OWNED_PROBE_STOP_SENT");
      return;
    }
    ProbeScenario scenario = args.length == 0 ? null : ProbeScenario.forOperation(args[0]);
    if ((args.length != 2 && args.length != 3)
        || !(args[0].equals("create")
            || args[0].equals("recover")
            || args[0].equals("configs")
            || args[0].equals("configs-fast")
            || scenario != null)) throw new IllegalArgumentException();
    UUID nonce = UUID.fromString(args[1]);
    if (!nonce.toString().equals(args[1])) throw new IllegalArgumentException();
    Path journal = Path.of("/data/local/tmp/homepod-direct-" + nonce + ".json");
    HalPortProbe probe =
        new HalPortProbe(
            nonce,
            args.length == 3 ? Integer.parseInt(args[2]) : 44100,
            args[0].equals("configs-fast"));
    JSONObject saved;
    if (!args[0].equals("recover")) {
      saved =
          probe
              .state
              .snapshot()
              .put("epoch", probe.epoch)
              .put("streamLedger", new JSONArray())
              .put("zeroOnly", scenario != null && scenario.zeroFrames > 0);
      Files.write(
          journal,
          saved.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      Files.setPosixFilePermissions(journal, PosixFilePermissions.fromString("rw-------"));
    } else {
      if (Files.isSymbolicLink(journal)
          || Files.size(journal) > 65536
          || ((Number) Files.getAttribute(journal, "unix:uid", LinkOption.NOFOLLOW_LINKS))
                  .intValue()
              != android.os.Process.myUid())
        throw new SecurityException("Invalid ownership journal");
      saved = new JSONObject(readString(journal));
    }
    if (!probe.epoch.equals(saved.getString("epoch"))) {
      System.out.println("{\"oldModuleGone\":true,\"mutations\":0}");
      return;
    }
    // Legacy journals cannot prove that an open reply was delivered. Never reuse them
    // to infer an empty stream list (the original open-only failure did exactly that).
    if (args[0].equals("recover") && !saved.has("streamLedger"))
      throw new IllegalStateException(
          "Legacy journal has unknown stream state; automatic cleanup refused");
    var recovered = new ArrayList<StreamOpenLedger.Entry>();
    var entries = saved.optJSONArray("streamLedger");
    if (entries != null)
      for (int i = 0; i < entries.length(); i++) {
        var e = entries.getJSONObject(i);
        recovered.add(
            new StreamOpenLedger.Entry(
                e.getInt("config"),
                e.getBoolean("input"),
                StreamOpenLedger.Phase.valueOf(e.getString("phase"))));
      }
    final JSONObject document = saved;
    probe.ledger =
        new StreamOpenLedger(
            values -> {
              var array = new JSONArray();
              for (var e : values)
                array.put(
                    new JSONObject()
                        .put("config", e.configId())
                        .put("input", e.input())
                        .put("phase", e.phase().name()));
              document.put("streamLedger", array);
              Path next = journal.resolveSibling(journal.getFileName() + ".next");
              Files.write(
                  next,
                  document.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.WRITE);
              Files.setPosixFilePermissions(next, PosixFilePermissions.fromString("rw-------"));
              try (var channel =
                  java.nio.channels.FileChannel.open(next, StandardOpenOption.WRITE)) {
                channel.force(true);
              }
              Files.move(
                  next,
                  journal,
                  java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                  java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            },
            recovered);
    var baseline = decode(saved, probe.epoch);
    var owner = new OwnedHalResources(probe, baseline, probe.tag);
    var watchdog =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r);
              t.setDaemon(true);
              return t;
            });
    watchdog.schedule(
        () -> {
          probe.stopRequested.set(true);
          probe.closeStreams();
          owner.cleanup();
        },
        15,
        TimeUnit.SECONDS);
    var created = new JSONArray();
    var minimumFrames = new JSONArray();
    var opened = new JSONArray();
    JSONObject mapping = null;
    Exception failure = null;
    OwnedHalResources.Result result;
    try {
      if (!args[0].equals("recover")) {
        int output = probe.createVirtualPort(143), input = probe.createVirtualPort(11);
        created.put(output);
        created.put(input);
        if (args[0].startsWith("configs") || scenario != null) {
          int outMix = probe.configure(3, false, true), inMix = probe.configure(4, true, true);
          int outDevice = probe.configure(output, false, false),
              inDevice = probe.configure(input, true, false);
          var outPatch = probe.patch(outMix, outDevice);
          var inPatch = probe.patch(inDevice, inMix);
          minimumFrames.put(outPatch.minimumFrames);
          minimumFrames.put(inPatch.minimumFrames);
          if (scenario != null) {
            opened.put(probe.open(outMix, false, outPatch.minimumFrames));
            opened.put(probe.open(inMix, true, inPatch.minimumFrames));
            mapping =
                HalQueueMapping.inspectInChild(
                    probe.streams.stream().map(stream -> stream.descriptor).toList(),
                    nonce.toString(),
                    scenario);
          }
        }
      }
    } catch (Exception error) {
      failure = error;
    } finally {
      probe.closeStreams();
      result = owner.cleanup();
      watchdog.shutdownNow();
    }
    var report =
        new JSONObject()
            .put("created", created)
            .put("cleanupComplete", result.complete())
            .put("oldInstanceGone", result.oldInstanceGone())
            .put("noStreamOpenAttempted", !probe.ledger.attempted())
            .put("openReplyUncertain", probe.ledger.replyUncertain())
            .put("streams", opened)
            .put("streamsClosed", probe.streamsClosed())
            .put("sampleRate", probe.rate)
            .put("minimumFrames", minimumFrames)
            .put("fastRequested", probe.fast)
            .put("cleanupErrors", new JSONArray(result.errors()));
    report.put("resourcesRestoredExactly", probe.snapshot().equals(baseline));
    if (mapping != null) report.put("mapping", mapping);
    if (failure != null)
      report.put("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
    System.out.println(report);
    if (!result.complete()) throw new IllegalStateException("Owned port cleanup incomplete");
    if (failure != null) throw failure;
  }

  private static int streamsCount(HalPortProbe probe) {
    return probe.streams.size();
  }
}
