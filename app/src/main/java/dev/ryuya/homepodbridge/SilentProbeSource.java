package dev.ryuya.homepodbridge;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit capture-only benchmark source: every byte is zero, never caller-provided audio. */
public final class SilentProbeSource implements AutoCloseable {
  private final AtomicBoolean closed = new AtomicBoolean();
  private AudioTrack track;
  private volatile long writtenFrames;

  public long writtenFrames() {
    return writtenFrames;
  }

  public void start() {
    new Thread(this::pump, "HomePod-zero-input").start();
  }

  private void pump() {
    AudioTrack created = null;
    try {
      if (closed.get()) return;
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
      created =
          new AudioTrack.Builder()
              .setAudioAttributes(
                  new AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_MEDIA)
                      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                      .build())
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setSampleRate(44100)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                      .build())
              .setBufferSizeInBytes(928 * 4 * 2)
              .setTransferMode(AudioTrack.MODE_STREAM)
              .build();
      synchronized (this) {
        if (closed.get()) return;
        track = created;
      }
      byte[] zeros = new byte[928 * 4];
      created.play();
      while (!closed.get()) {
        int offset = 0;
        while (offset < zeros.length && !closed.get()) {
          int count =
              created.write(zeros, offset, zeros.length - offset, AudioTrack.WRITE_BLOCKING);
          if (count <= 0) return;
          writtenFrames += count / 4;
          offset += count;
        }
      }
    } catch (RuntimeException ignored) {
      // An unsupported source makes diagnostics inconclusive; normal capture is unaffected.
    } finally {
      synchronized (this) {
        track = null;
      }
      if (created != null) {
        try {
          created.stop();
        } catch (RuntimeException ignored) {
        }
        created.release();
      }
    }
  }

  public void close() {
    closed.set(true);
    synchronized (this) {
      if (track != null) {
        try {
          track.stop();
        } catch (RuntimeException ignored) {
        }
      }
    }
  }
}
