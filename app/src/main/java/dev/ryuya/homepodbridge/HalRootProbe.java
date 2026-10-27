package dev.ryuya.homepodbridge;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Exact-build read-only inspection; optional bounded quiescence check never writes memory. */
public final class HalRootProbe {
  private static final String MODULE = "/vendor/lib64/libaudioserviceexampleimpl.so";
  private static final String PIPE = "/vendor/lib64/libnbaio_mono.so";
  private static final String MODULE_SHA =
      "ef1bad7d1ae1d66a6ac6b39a009fba43e00031bb9a3316e435965d4e91564d61";
  private static final String PIPE_SHA =
      "c363ecab034babaa344163bc4aaf67dfe42945055c433e0640ba555b86d159aa";

  public static void main(String[] args) {
    try {
      if (android.os.Process.myUid() != 0
          || args.length < 1
          || args.length > 2
          || (args.length == 2
              && !List.of(
                      "freeze",
                      "tune",
                      "restore",
                      "brief",
                      "restore-brief",
                      "minimum",
                      "restore-minimum",
                      "direct",
                      "restore-direct",
                      "direct-752",
                      "restore-direct-752",
                      "direct-768",
                      "restore-direct-768",
                      "tiny-period",
                      "restore-tiny-period",
                      "latency-window",
                      "restore-latency-window")
                  .contains(args[1])
              && manualOperation(args[1]) < 0
              && dev.ryuya.homepodbridge.halprobe.PhonePreparation.parse(args[1]) == null)
          || !(args[0].equals("none") || args[0].matches("[0-9]+:ap:[0-9]+mixp:[0-9]+")))
        throw new IOException("Invalid probe context");
      verify(MODULE, MODULE_SHA);
      verify(PIPE, PIPE_SHA);
      java.lang.Process query =
          new ProcessBuilder("dumpsys", "--pid", "android.hardware.audio.core.IModule/r_submix")
              .start();
      String pid =
          new String(query.getInputStream().readNBytes(128), StandardCharsets.US_ASCII).trim();
      if (!query.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) || !pid.matches("[0-9]{1,7}"))
        throw new IOException("Missing submix service");
      Path proc = Path.of("/proc", pid);
      String status =
          new String(Files.readAllBytes(proc.resolve("status")), StandardCharsets.UTF_8);
      if (!status.matches("(?s).*\\nUid:\\s+1041\\s+1041\\s+1041\\s+1041\\s*\\n.*"))
        throw new IOException("Unexpected service identity");
      if (status.contains("State:\tT") || status.contains("State:\tt"))
        throw new IOException("Service already stopped");
      String command =
          new String(Files.readAllBytes(proc.resolve("cmdline")), StandardCharsets.UTF_8);
      if (!command.split("\u0000")[0].endsWith("audiohalservice.qti"))
        throw new IOException("Unexpected service executable");
      try (Memory memory = new Memory(proc)) {
        long base = memory.base(MODULE), pipeBase = memory.base(PIPE);
        byte[] before = memory.read(base + 0x1b4730, 24);
        long count = number(before, 16);
        if (count < 0 || count > 16) throw new IOException("Unrecognized route count");
        if (args[0].equals("none")) {
          System.out.println(
              "Read-only exact-build service verified; routes="
                  + count
                  + " mappedRegions="
                  + memory.maps.size()
                  + "; no owned registration; no route objects inspected");
          return;
        }
        List<Long> pending = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        pending.add(number(before, 8));
        String report = null;
        long observedTarget = -1;
        while (!pending.isEmpty()) {
          long node = pointer(pending.remove(pending.size() - 1));
          if (node == 0) continue;
          if (seen.size() >= count || !seen.add(node)) throw new IOException("Unstable route tree");
          byte[] entry = memory.read(node, 80);
          pending.add(number(entry, 0));
          pending.add(number(entry, 8));
          if (ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).getInt(56) != 0) continue;
          String address = memory.string(node + 32);
          if (!address.equals(args[0])) continue;
          if (report != null) throw new IOException("Ambiguous registration");
          long route = pointer(number(entry, 64));
          byte[] routeData = memory.read(route, 144);
          ByteBuffer config = ByteBuffer.wrap(routeData).order(ByteOrder.LITTLE_ENDIAN);
          if (config.getInt(0x6c) != 1 || config.getInt(0x28) != 44100 || config.getLong(0x58) != 4)
            throw new IOException("Unexpected owned route format or reference count");
          long sink = pointer(config.getLong(0x80));
          byte[] pipeData = memory.read(sink, 256);
          long capacity = number(pipeData, 0x40), target = number(pipeData, 0xe8);
          observedTarget = target;
          if (pointer(number(pipeData, 0)) != pipeBase + 0x8018
              || number(pipeData, 0x30) != 4
              || capacity != 4096
              || target <= 0
              || target > capacity) throw new IOException("Unexpected owned pipe identity");
          if (!Arrays.equals(entry, memory.read(node, 80))
              || pointer(number(memory.read(route + 0x80, 8), 0)) != sink)
            throw new IOException("Owned route changed during observation");
          report =
              "Read-only owned route verified; references=1 rate=44100 frameBytes=4 capacityFrames="
                  + capacity
                  + " targetFillFrames="
                  + target
                  + " targetFillMs="
                  + target * 1000.0 / 44100
                  + "; no memory modified; concurrent snapshot, not a lifetime/quiescence"
                  + " guarantee";
        }
        if (seen.size() != count || !Arrays.equals(before, memory.read(base + 0x1b4730, 24)))
          throw new IOException("Route set changed during observation");
        System.out.println(report == null ? "No matching live owned route" : report);
        if (report != null && args.length == 2) {
          if (args[1].startsWith("restore") && observedTarget == 2587) {
            System.out.println("Original reserve already present");
            return;
          }
          String apk = System.getenv("CLASSPATH");
          if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk"))
            throw new IOException("Invalid native helper path");
          System.load(apk + "!/lib/arm64-v8a/libhomepod_hal.so");
          var preparation = dev.ryuya.homepodbridge.halprobe.PhonePreparation.parse(args[1]);
          if (preparation != null) {
            String reserve =
                freezeCheck(
                    Integer.parseInt(pid), base, pipeBase, args[0], preparation.reserveOperation());
            System.out.println(reserve);
            if (!reserve.contains(
                "Owned reserve changed: 2587 -> " + preparation.timing().reserveFrames() + ";"))
              return;
            String window =
                freezeCheck(
                    Integer.parseInt(pid), base, pipeBase, args[0], preparation.windowOperation());
            System.out.println(window);
            if (!window.contains(
                "Owned latency window changed: 4096 -> "
                    + preparation.timing().latencyWindowFrames()
                    + ";"))
              System.out.println(
                  freezeCheck(
                      Integer.parseInt(pid),
                      base,
                      pipeBase,
                      args[0],
                      preparation.rollbackOperation()));
            return;
          }
          int operation =
              List.of(
                      "freeze",
                      "tune",
                      "restore",
                      "brief",
                      "restore-brief",
                      "minimum",
                      "restore-minimum",
                      "direct",
                      "restore-direct",
                      "direct-752",
                      "restore-direct-752",
                      "direct-768",
                      "restore-direct-768",
                      "tiny-period",
                      "restore-tiny-period",
                      "latency-window",
                      "restore-latency-window")
                  .indexOf(args[1]);
          if (operation < 0) operation = manualOperation(args[1]);
          System.out.println(
              freezeCheck(Integer.parseInt(pid), base, pipeBase, args[0], operation));
        }
      }
    } catch (Exception error) {
      System.out.println(
          "Read-only route probe unavailable: "
              + error.getClass().getSimpleName()
              + ": "
              + error.getMessage());
    }
  }

  private static int manualOperation(String value) {
    var m =
        java.util.regex.Pattern.compile(
                "(reserve|restore-reserve|window|restore-window)-([0-9]{2,4})")
            .matcher(value);
    if (!m.matches()) return -1;
    int number = Integer.parseInt(m.group(2));
    String kind = m.group(1);
    if (kind.endsWith("reserve")) {
      if (number < 64 || number > 800) return -1;
      return (kind.startsWith("restore") ? 20000 : 10000) + number;
    }
    boolean supported = false;
    for (int ms = 1; ms <= 21; ms++)
      if (new dev.ryuya.homepodbridge.halprobe.PhoneTiming(ms, 96).latencyWindowFrames() == number)
        supported = true;
    return supported ? (kind.startsWith("restore") ? 40000 : 30000) + number : -1;
  }

  private static void verify(String file, String expected) throws Exception {
    if (Files.size(Path.of(file)) > 4 * 1024 * 1024)
      throw new IOException("Unexpected library size");
    byte[] bytes = Files.readAllBytes(Path.of(file));
    if (bytes.length > 4 * 1024 * 1024) throw new IOException("Unexpected library size");
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder hex = new StringBuilder();
    for (byte value : digest) hex.append(String.format("%02x", value & 255));
    if (!hex.toString().equals(expected)) throw new IOException("Unsupported HAL build");
  }

  private static long number(byte[] bytes, int offset) {
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(offset);
  }

  private static long pointer(long value) {
    return value & 0x00ffffffffffffffL;
  }

  private record Mapping(long begin, long end, boolean readable, long offset, String path) {}

  private static final class Memory implements AutoCloseable {
    private final RandomAccessFile file;
    private final List<Mapping> maps = new ArrayList<>();

    Memory(Path proc) throws IOException {
      for (String line : Files.readAllLines(proc.resolve("maps"))) {
        String[] fields = line.split("\\s+", 6), range = fields[0].split("-");
        maps.add(
            new Mapping(
                Long.parseLong(range[0], 16),
                Long.parseLong(range[1], 16),
                fields[1].startsWith("r"),
                Long.parseLong(fields[2], 16),
                fields.length == 6 ? fields[5] : ""));
      }
      file = new RandomAccessFile(proc.resolve("mem").toFile(), "r");
    }

    long base(String path) throws IOException {
      return maps.stream()
          .filter(m -> m.path.equals(path) && m.offset == 0 && m.readable)
          .findFirst()
          .orElseThrow(() -> new IOException("Module not mapped"))
          .begin;
    }

    byte[] read(long address, int length) throws IOException {
      long at = pointer(address);
      if (at < 65536
          || length < 0
          || length > 4096
          || maps.stream().noneMatch(m -> m.readable && at >= m.begin && at + length <= m.end))
        throw new IOException("Invalid bounded memory range");
      byte[] result = new byte[length];
      file.seek(at);
      file.readFully(result);
      return result;
    }

    String string(long address) throws IOException {
      byte[] value = read(address, 24);
      boolean large = (value[0] & 1) != 0;
      long length = large ? number(value, 8) : (value[0] & 255) >> 1;
      if (length < 0 || length > 128) throw new IOException("Invalid registration length");
      byte[] text = read(large ? number(value, 16) : address + 1, (int) length + 1);
      if (text[(int) length] != 0) throw new IOException("Invalid registration terminator");
      return new String(text, 0, (int) length, StandardCharsets.UTF_8);
    }

    public void close() throws IOException {
      file.close();
    }
  }

  private HalRootProbe() {}

  private static native String freezeCheck(
      int pid, long module, long mono, String registration, int operation);
}
