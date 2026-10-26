package dev.ryuya.homepodbridge;

import android.content.Context;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Private bounded zero-only HAL handoff. Closing stdout requests cleanup; never kills its owner.
 */
final class RootZeroInput implements AutoCloseable {
  final String nonce = UUID.randomUUID().toString();
  private final Process process;
  private final AtomicBoolean closed = new AtomicBoolean();

  RootZeroInput(Context context) throws java.io.IOException {
    if (BridgeState.connected || BridgeState.volume != 0)
      throw new java.io.IOException("Muted idle required");
    String apk = context.getApplicationInfo().sourceDir;
    if (!apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk")) throw new SecurityException();
    context.deleteFile("last-hal-zero.txt");
    process =
        new ProcessBuilder(
                "su",
                "-c",
                "CLASSPATH='"
                    + apk
                    + "' exec timeout -k 2 30 app_process /system/bin"
                    + " dev.ryuya.homepodbridge.halprobe.HalPortProbe policy-forward "
                    + nonce
                    + " 44100")
            .start();
    Context app = context.getApplicationContext();
    Thread diagnostics =
        new Thread(
            () -> {
              try (InputStream errors = process.getErrorStream()) {
                var kept = new java.io.ByteArrayOutputStream();
                byte[] block = new byte[1024];
                int count;
                while ((count = errors.read(block)) != -1) {
                  int remaining = 8192 - kept.size();
                  if (remaining > 0) kept.write(block, 0, Math.min(remaining, count));
                }
                try (var output = app.openFileOutput("last-hal-zero.txt", Context.MODE_PRIVATE)) {
                  output.write(("nonce=" + nonce + "\n").getBytes(StandardCharsets.US_ASCII));
                  output.write(kept.toByteArray());
                }
              } catch (java.io.IOException ignored) {
                // No PCM is ever read from or written to the diagnostic log.
              } finally {
                try {
                  process.getOutputStream().close();
                } catch (java.io.IOException ignored) {
                }
              }
            },
            "HomePod-zero-HAL-result");
    diagnostics.setDaemon(true);
    diagnostics.start();
  }

  InputStream input() {
    return process.getInputStream();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    try {
      process.getOutputStream().close();
    } catch (java.io.IOException ignored) {
    }
    Thread closer =
        new Thread(
            () -> {
              try {
                process.getInputStream().close();
              } catch (java.io.IOException ignored) {
              }
            },
            "HomePod-zero-HAL-close");
    closer.setDaemon(true);
    closer.start();
    // EPIPE reaches the isolated writer. The Java owner closes its Binders and owned IDs.
  }
}
