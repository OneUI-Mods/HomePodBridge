package dev.ryuya.homepodbridge;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import java.io.DataInputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** App side of a root-owned playback policy. LocalSocket close is immediate and cancels capture. */
final class RootHalInput implements AutoCloseable {
  static final class StartupException extends java.io.IOException {
    final boolean safeFallback;

    StartupException(Throwable cause, boolean safe) {
      super("Dedicated capture unavailable", cause);
      safeFallback = safe;
    }
  }

  final String nonce = UUID.randomUUID().toString();
  private Process process;
  private LocalSocket socket;
  private java.io.InputStream data;
  private final CountDownLatch finished = new CountDownLatch(1);
  private volatile JSONObject result;
  String registration;

  RootHalInput(Context context, String source, boolean whole, boolean zero, boolean pulse)
      throws StartupException {
    try {
      if (BridgeState.connected
          || BridgeState.volume != 0
          || !source.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) throw new SecurityException();
      String apk = context.getApplicationInfo().sourceDir;
      if (!apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk")) throw new SecurityException();
      String mode =
          zero
              ? (pulse ? "test-cycle" : whole ? "test-whole" : "test-selected")
              : whole ? "whole" : "selected";
      try (var server = new LocalServerSocket("hpb-live-" + nonce)) {
        process =
            new ProcessBuilder(
                    "su",
                    "-c",
                    "CLASSPATH='"
                        + apk
                        + "' exec app_process /system/bin "
                        + "dev.ryuya.homepodbridge.halprobe.HalCaptureHost "
                        + nonce
                        + " "
                        + mode
                        + " "
                        + source)
                .redirectError(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")))
                .start();
        Context app = context.getApplicationContext();
        Thread monitor =
            new Thread(
                () -> {
                  try (var input =
                      new java.io.BufferedReader(
                          new java.io.InputStreamReader(
                              process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null)
                      if (line.startsWith("{") && line.length() <= 16384) {
                        result = new JSONObject(line);
                        finished.countDown();
                        try (var output =
                            app.openFileOutput("last-hal-capture.txt", Context.MODE_PRIVATE)) {
                          output.write(
                              ("nonce=" + nonce + "\n" + line)
                                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                      }
                  } catch (Exception ignored) {
                  } finally {
                    finished.countDown();
                  }
                },
                "HomePod-HAL-result");
        monitor.setDaemon(true);
        monitor.start();
        var poll = new android.system.StructPollfd();
        poll.fd = server.getFileDescriptor();
        poll.events = (short) android.system.OsConstants.POLLIN;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline && process.isAlive()) {
          android.system.Os.poll(new android.system.StructPollfd[] {poll}, 100);
          if ((poll.revents & android.system.OsConstants.POLLIN) != 0) break;
        }
        if ((poll.revents & android.system.OsConstants.POLLIN) == 0)
          throw new java.io.IOException("No HAL host");
        socket = server.accept();
        socket.setSoTimeout(8000);
        if (socket.getPeerCredentials().getUid() != 0)
          throw new SecurityException("Root host required");
        var input = new DataInputStream(socket.getInputStream());
        if (input.readInt() != 0x48505231 || !input.readUTF().equals(nonce))
          throw new SecurityException("Capture handshake mismatch");
        registration = input.readUTF();
        if (!registration.matches("[0-9]+:ap:[0-9]+mixp:[0-9]+")) throw new SecurityException();
        byte[] first = new byte[200];
        input.readFully(first);
        // Decode it before accepting the backend. The first record may represent idle silence.
        new PcmPipe(
                java.nio.channels.Channels.newChannel(new java.io.ByteArrayInputStream(first)),
                true,
                System::nanoTime,
                PcmPipe.HAL_MAGIC)
            .read(java.nio.ByteBuffer.allocateDirect(176));
        socket.setSoTimeout(3000);
        data =
            new java.io.SequenceInputStream(
                new java.io.ByteArrayInputStream(first), socket.getInputStream());
      }
    } catch (Throwable unavailable) {
      close();
      boolean safe = awaitCleanup(5000);
      throw new StartupException(unavailable, safe);
    }
  }

  java.io.InputStream input() {
    return data;
  }

  boolean awaitCleanup(long milliseconds) {
    if (process == null) return true;
    try {
      finished.await(milliseconds, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    JSONObject report = result;
    return report != null
        && (report.optBoolean("resourcesRestoredExactly")
            || report.optBoolean("ownedResourcesReleased"));
  }

  @Override
  public void close() {
    try {
      if (socket != null) socket.close();
    } catch (java.io.IOException ignored) {
    }
    try {
      if (process != null) process.getOutputStream().close();
    } catch (java.io.IOException ignored) {
    }
  }
}
