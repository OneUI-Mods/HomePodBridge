package dev.ryuya.homepodbridge;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Explicit local reference only. Fixed signals, verified built-in speaker, restored media volume.
 */
final class PhoneAcousticReference implements AutoCloseable {
  static final int RATE = 48000, COUNT = 2;
  static final int[] LOW = {1800, 4300}, HIGH = {4600, 1500}, CODE = {0x17D4, 0xC2B9};
  private final Context context;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final CountDownLatch ready = new CountDownLatch(1);
  private final ArrayList<long[]> timestamps = new ArrayList<>();
  private final long[] markers = new long[COUNT], markerFrames = new long[COUNT];
  private final Thread worker;
  private volatile long armAt;
  private volatile String failure;
  private int oldVolume = -1,
      volume = -1,
      maxVolume,
      deviceType,
      bufferFrames,
      performance,
      underruns;
  private boolean volumeRestored;

  PhoneAcousticReference(Context context) {
    this.context = context;
    worker = new Thread(this::pump, "HomePod-phone-reference");
  }

  void start() throws Exception {
    worker.start();
    if (!ready.await(3, TimeUnit.SECONDS)
        || failure != null
        || deviceType != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
      close();
      throw new IllegalStateException("Phone speaker reference unavailable: " + failure);
    }
  }

  void arm(long microphoneStart) {
    armAt = microphoneStart;
  }

  private void pump() {
    AudioTrack track = null;
    AudioManager manager = context.getSystemService(AudioManager.class);
    try {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
      maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      oldVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
      if (maxVolume < 4 || oldVolume > maxVolume / 4)
        throw new IllegalStateException("Phone media volume exceeds diagnostic ceiling");
      if (oldVolume == 0) manager.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0);
      volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
      if (volume < 1 || volume > maxVolume / 4)
        throw new IllegalStateException("Phone diagnostic volume not bounded");
      AudioDeviceInfo speaker = null;
      for (var device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
        if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
          speaker = device;
          break;
        }
      if (speaker == null) throw new IllegalStateException("Built-in speaker missing");
      int minimum =
          AudioTrack.getMinBufferSize(
              RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
      if (minimum <= 0) throw new IllegalStateException("Phone output format unsupported");
      track =
          new AudioTrack.Builder()
              .setAudioAttributes(
                  new AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_MEDIA)
                      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                      .build())
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setSampleRate(RATE)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .build())
              .setBufferSizeInBytes(Math.max(minimum, 480 * 4))
              .setTransferMode(AudioTrack.MODE_STREAM)
              .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
              .build();
      if (track.getState() != AudioTrack.STATE_INITIALIZED || !track.setPreferredDevice(speaker))
        throw new IllegalStateException("Phone speaker route refused");
      track.setBufferSizeInFrames(480);
      bufferFrames = track.getBufferSizeInFrames();
      performance = track.getPerformanceMode();
      track.setStartThresholdInFrames(Math.min(bufferFrames, 240));
      short[] block = new short[480];
      long written = 0;
      int prime = track.write(block, 0, block.length, AudioTrack.WRITE_NON_BLOCKING);
      if (prime > 0) written = prime / 2;
      track.play();
      AudioTimestamp timestamp = new AudioTimestamp();
      int next = 0, active = -1, toneFrame = 0;
      while (!closed.get()) {
        var routed = track.getRoutedDevice();
        if (routed == null && deviceType != 0)
          throw new IllegalStateException("Phone speaker route became unknown");
        if (routed != null) {
          deviceType = routed.getType();
          if (deviceType != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            throw new IllegalStateException("Phone reference route changed");
          ready.countDown();
        }
        java.util.Arrays.fill(block, (short) 0);
        long now = System.nanoTime();
        int pending = -1, pendingFrame = 0;
        if (deviceType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            && armAt > 0
            && next < COUNT
            && now >= armAt + 900_000_000L + next * 600_000_000L) {
          active = next++;
          toneFrame = 0;
          pending = active;
          pendingFrame = 0;
        }
        for (int f = 0; f < 240; f++)
          if (active >= 0) {
            double source = toneFrame * (double) AcousticSignal.RATE / RATE;
            int left = (int) source;
            if (left >= AcousticSignal.TONE_FRAMES) {
              active = -1;
              continue;
            }
            double fraction = source - left;
            short a = AcousticSignal.sample(LOW[active], HIGH[active], CODE[active], left),
                b = AcousticSignal.sample(LOW[active], HIGH[active], CODE[active], left + 1);
            short sample = (short) Math.round(a + (b - a) * fraction);
            block[f * 2] = sample;
            block[f * 2 + 1] = sample;
            toneFrame++;
          }
        int offset = 0;
        while (offset < block.length && !closed.get()) {
          int count = track.write(block, offset, block.length - offset, AudioTrack.WRITE_BLOCKING);
          long acceptedAt = System.nanoTime();
          if (count <= 0 || count % 2 != 0)
            throw new IllegalStateException("Phone reference write failed");
          if (pending >= 0 && pendingFrame * 2 >= offset && pendingFrame * 2 < offset + count) {
            markers[pending] = acceptedAt + (pendingFrame - offset / 2) * 1_000_000_000L / RATE;
            markerFrames[pending] = written + pendingFrame - offset / 2;
            pending = -1;
          }
          written += count / 2;
          offset += count;
        }
        if (track.getTimestamp(timestamp) && timestamp.nanoTime > 0 && timestamp.framePosition > 0)
          timestamps.add(
              new long[] {timestamp.framePosition, timestamp.nanoTime, written, System.nanoTime()});
      }
    } catch (Exception error) {
      failure = error.getClass().getSimpleName() + ": " + error.getMessage();
    } finally {
      if (track != null) {
        underruns = track.getUnderrunCount();
        try {
          track.stop();
        } catch (IllegalStateException ignored) {
        }
        track.release();
      }
      if (oldVolume >= 0 && oldVolume <= maxVolume / 4) {
        try {
          int currentVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
          if (currentVolume == volume)
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0);
          volumeRestored = manager.getStreamVolume(AudioManager.STREAM_MUSIC) == oldVolume;
        } catch (RuntimeException ignored) {
        }
      }
      ready.countDown();
    }
  }

  JSONObject metadata() throws Exception {
    var result =
        new JSONObject()
            .put("rate", RATE)
            .put("routedDeviceType", deviceType)
            .put("bufferFrames", bufferFrames)
            .put("performanceMode", performance)
            .put("underruns", underruns)
            .put("volumeBefore", oldVolume)
            .put("volumeDuring", volume)
            .put("volumeMax", maxVolume)
            .put("volumeRestored", volumeRestored);
    if (failure != null) result.put("failure", failure);
    if (worker.isAlive())
      return result
          .put("failure", "Phone reference is still active")
          .put("anchors", new JSONArray())
          .put("tones", new JSONArray());
    JSONArray anchors = new JSONArray(), tones = new JSONArray();
    for (long[] stamp : timestamps) anchors.put(new JSONArray(stamp));
    for (int i = 0; i < COUNT; i++)
      tones.put(
          new JSONObject()
              .put("destination", "phone")
              .put("enqueuedAtNs", markers[i])
              .put("trackFrame", markerFrames[i])
              .put("lowHz", LOW[i])
              .put("highHz", HIGH[i])
              .put("phaseCode", CODE[i]));
    return result.put("anchors", anchors).put("tones", tones);
  }

  @Override
  public void close() {
    closed.set(true);
    try {
      worker.join(1500);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    if (worker.isAlive()) failure = "Phone reference cleanup unconfirmed";
  }
}
