package dev.ryuya.homepodbridge;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/** Explicit, read-only inspection of a fixed set of public HAL implementation binaries. */
final class HalInspection {
  private static final String[] FILES = {
    "/vendor/lib64/libaudioserviceexampleimpl.so",
    "/vendor/lib64/libnbaio_mono.so",
    "/vendor/lib64/hw/audio.r_submix.default.so"
  };
  private static final int MAX_BYTES = 16 * 1024 * 1024;
  private static String status = "Not requested";
  private static byte[] image;
  private static int selected = -1;
  private static boolean running;

  static synchronized void requestRoute(android.content.Context context, String registration) {
    requestRoute(context, registration, 0);
  }

  static synchronized void requestRoute(
      android.content.Context context, String registration, boolean freeze) {
    requestRoute(context, registration, freeze ? 1 : 0);
  }

  static synchronized String result() {
    return running ? null : status;
  }

  static synchronized void requestRoute(
      android.content.Context context, String registration, int operation) {
    if (operation < 0 || operation > 3) return;
    if (running) return;
    String address = registration == null ? "none" : registration;
    if (!address.equals("none") && !address.matches("[0-9]+:ap:[0-9]+mixp:[0-9]+")) {
      status = "Unrecognized owned registration";
      return;
    }
    String apk = context.getApplicationInfo().sourceDir;
    if (!apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk")) {
      status = "Unrecognized module path";
      return;
    }
    running = true;
    image = null;
    selected = 3;
    status = "Checking owned HAL route";
    String action = new String[] {"", " freeze", " tune", " restore"}[operation];
    new Thread(
            () -> {
              try {
                byte[] report =
                    readCommand(
                        "CLASSPATH='"
                            + apk
                            + "' exec app_process /system/bin dev.ryuya.homepodbridge.HalRootProbe"
                            + " '"
                            + address
                            + "'"
                            + action);
                synchronized (HalInspection.class) {
                  image = report;
                  status = new String(report, java.nio.charset.StandardCharsets.UTF_8);
                }
              } catch (Exception error) {
                synchronized (HalInspection.class) {
                  status = "Route inspection unavailable: " + error.getClass().getSimpleName();
                }
              } finally {
                synchronized (HalInspection.class) {
                  running = false;
                }
              }
            },
            "HomePod-route-inspection")
        .start();
  }

  static synchronized void request(int index) {
    if (running || index < 0 || index >= FILES.length) return;
    running = true;
    selected = index;
    image = null;
    status = "Inspecting " + FILES[index];
    new Thread(
            () -> {
              try {
                byte[] bytes = read(FILES[index]);
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                StringBuilder hex = new StringBuilder();
                for (byte value : digest) hex.append(String.format("%02x", value & 255));
                synchronized (HalInspection.class) {
                  image = bytes;
                  status = "Read-only ELF bytes=" + bytes.length + " sha256=" + hex;
                }
              } catch (Exception error) {
                synchronized (HalInspection.class) {
                  status = "Inspection unavailable: " + error.getClass().getSimpleName();
                }
              } finally {
                synchronized (HalInspection.class) {
                  running = false;
                }
              }
            },
            "HomePod-HAL-inspection")
        .start();
  }

  private static byte[] read(String file) throws IOException, InterruptedException {
    byte[] result = readCommand("exec cat " + file);
    if (result.length < 64
        || result[0] != 127
        || result[1] != 'E'
        || result[2] != 'L'
        || result[3] != 'F') throw new IOException("Not ELF");
    return result;
  }

  private static byte[] readCommand(String command) throws IOException, InterruptedException {
    java.lang.Process helper = new ProcessBuilder("su", "-c", command).start();
    try (var output = helper.getInputStream();
        var errors = helper.getErrorStream();
        var bytes = new ByteArrayOutputStream()) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      byte[] block = new byte[16384];
      while (helper.isAlive() || output.available() > 0) {
        if (System.nanoTime() >= deadline) throw new IOException("Inspection timed out");
        int available = output.available();
        if (available > 0) {
          int count = output.read(block, 0, Math.min(block.length, available));
          if (count < 0) break;
          if (bytes.size() + count > MAX_BYTES) throw new IOException("Image too large");
          bytes.write(block, 0, count);
        } else Thread.sleep(5);
        // Drain only; diagnostics never include root prompts or arbitrary stderr contents.
        while (errors.available() > 0)
          errors.read(block, 0, Math.min(block.length, errors.available()));
      }
      if (!helper.waitFor(1, TimeUnit.SECONDS) || helper.exitValue() != 0)
        throw new IOException("Read unavailable");
      return bytes.toByteArray();
    } finally {
      helper.destroy();
    }
  }

  static synchronized void dump(PrintWriter writer, boolean includeImage) {
    writer.println("HAL inspection index=" + selected + " running=" + running + " " + status);
    if (includeImage && image != null) {
      writer.println("HOMEPOD_HAL_IMAGE_BEGIN");
      writer.println(Base64.encodeToString(image, Base64.NO_WRAP));
      writer.println("HOMEPOD_HAL_IMAGE_END");
    }
  }

  private HalInspection() {}
}
