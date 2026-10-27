package dev.ryuya.homepodbridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit DUMP-only acoustic diagnostic. Mic samples are never forwarded or stored on disk. */
final class AcousticProbe implements AutoCloseable {
  private static final java.util.concurrent.atomic.AtomicReference<AcousticProbe> current =
      new java.util.concurrent.atomic.AtomicReference<>();
  private static final int MIC_RATE = 48000, MAX_SECONDS = 6;
  private final Context context;
  private final int volume;
  private final Consumer<String> status;
  private final Runnable finishedCallback;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicBoolean stopping = new AtomicBoolean();
  private final short[] samples = new short[MIC_RATE * MAX_SECONDS];
  private final ArrayList<long[]> anchors = new ArrayList<>();
  private final long[] markers = new long[AcousticSignal.COUNT];
  private final long[][] markerStages = new long[AcousticSignal.COUNT][];
  private volatile IBinder lifetime;
  private volatile boolean armed, finished, clearRequested;
  private volatile int sampleCount;
  private volatile String failure;
  private long armedAt, renderedFrames;
  private int pending = -1, pendingOffset;
  private int source;
  private long startedAt;
  private Thread recording;

  AcousticProbe(Context context, int volume, Consumer<String> status, Runnable finishedCallback) {
    if (volume < 1 || volume > 25)
      throw new IllegalArgumentException("Diagnostic volume must be 1..25");
    this.context = context;
    this.volume = volume;
    this.status = status;
    this.finishedCallback = finishedCallback;
  }

  void bind(IBinder ownedLifetime) {
    lifetime = ownedLifetime;
    current.set(this);
  }

  void connected(boolean success) {
    if (!success) {
      failure = "Receiver connection failed";
      stop();
      return;
    }
    if (!ownsConnection() || !BridgeState.connected) {
      failure = "Owned receiver session missing";
      stop();
      return;
    }
    recording = new Thread(this::record, "HomePod-acoustic-mic");
    recording.start();
  }

  private boolean ownsConnection() {
    return lifetime != null
        && BridgeState.capture != null
        && BridgeState.capture.lifetime() == lifetime;
  }

  static AcousticProbe forLifetime(IBinder owner) {
    AcousticProbe probe = current.get();
    return probe != null && probe.lifetime == owner && probe.armed && !probe.stopping.get()
        ? probe
        : null;
  }

  // Invoked only after the dedicated zero-only capture record is cleared, on its capture worker.
  void render(ByteBuffer pcm, int bytes) {
    pending = -1;
    if (!armed || stopping.get() || System.nanoTime() < armedAt) return;
    int frames = bytes / 4;
    for (int burst = 0; burst < AcousticSignal.COUNT; burst++) {
      long start = AcousticSignal.firstFrame(burst), end = start + AcousticSignal.TONE_FRAMES;
      if (start >= renderedFrames && start < renderedFrames + frames) {
        pending = burst;
        pendingOffset = (int) (start - renderedFrames);
      }
      for (int frame = 0; frame < frames; frame++) {
        long at = renderedFrames + frame;
        if (at >= start && at < end) {
          short value = AcousticSignal.sample(burst, (int) (at - start));
          pcm.putShort(frame * 4, value);
          pcm.putShort(frame * 4 + 2, value);
        }
      }
    }
    renderedFrames += frames;
  }

  void accepted() {
    if (pending >= 0) {
      synchronized (markers) {
        markers[pending] = System.nanoTime() + pendingOffset * 1_000_000_000L / AcousticSignal.RATE;
      }
      pending = -1;
    }
  }

  private void record() {
    AudioRecord mic = null;
    try {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
      if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
          != PackageManager.PERMISSION_GRANTED)
        throw new SecurityException("Microphone permission missing");
      boolean unprocessed =
          "true"
              .equals(
                  context
                      .getSystemService(AudioManager.class)
                      .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
      source =
          unprocessed
              ? MediaRecorder.AudioSource.UNPROCESSED
              : MediaRecorder.AudioSource.VOICE_RECOGNITION;
      int minimum =
          AudioRecord.getMinBufferSize(
              MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      if (minimum <= 0) throw new IllegalStateException("Microphone format unavailable");
      mic =
          new AudioRecord.Builder()
              .setAudioSource(source)
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setSampleRate(MIC_RATE)
                      .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .build())
              .setBufferSizeInBytes(Math.max(minimum, MIC_RATE * 2 / 5))
              .build();
      if (mic.getState() != AudioRecord.STATE_INITIALIZED)
        throw new IllegalStateException("Microphone unavailable");
      mic.startRecording();
      if (mic.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
        throw new IllegalStateException("Microphone did not start");
      startedAt = System.nanoTime();
      main.post(
          () -> {
            if (stopping.get() || !ownsConnection() || !BridgeState.connected) {
              failure = "Session changed before signal";
              stop();
              return;
            }
            // This limit is only for this explicit diagnostic; ordinary app volume remains 0..100.
            BridgeState.volume = volume;
            NativeSender.volume(volume);
            BridgeState.publish();
            armedAt = System.nanoTime() + 750_000_000L;
            armed = true;
            status.accept("ACOUSTIC_RECORDING volume=" + volume + " phoneSpeakerSignal=false");
          });
      short[] block = new short[480];
      AudioTimestamp timestamp = new AudioTimestamp();
      while (!stopping.get()
          && sampleCount < samples.length
          && System.nanoTime() - startedAt < MAX_SECONDS * 1_000_000_000L) {
        int count =
            mic.read(
                block,
                0,
                Math.min(block.length, samples.length - sampleCount),
                AudioRecord.READ_BLOCKING);
        long readAt = System.nanoTime();
        if (count <= 0) throw new IllegalStateException("Microphone read failed: " + count);
        System.arraycopy(block, 0, samples, sampleCount, count);
        sampleCount += count;
        int result = mic.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC);
        if (result == AudioRecord.SUCCESS && timestamp.nanoTime > 0 && timestamp.framePosition > 0)
          anchors.add(
              new long[] {sampleCount, timestamp.framePosition, timestamp.nanoTime, readAt});
      }
      if (anchors.size() < 5) failure = "Reliable microphone timestamps unavailable";
    } catch (Exception error) {
      failure = error.getClass().getSimpleName() + ": " + error.getMessage();
    } finally {
      stopping.set(true);
      armed = false;
      if (mic != null) {
        try {
          mic.stop();
        } catch (IllegalStateException ignored) {
        }
        mic.release();
      }
      current.compareAndSet(this, null);
      if (clearRequested) {
        Arrays.fill(samples, (short) 0);
        sampleCount = 0;
      }
      finished = true;
      main.post(
          () -> {
            if (ownsConnection()) {
              NativeSender.volume(0);
              BridgeState.volume = 0;
              BridgeState.publish();
            }
            status.accept(
                failure == null
                    ? "ACOUSTIC_COMPLETE micFrames=" + sampleCount + " anchors=" + anchors.size()
                    : "ACOUSTIC_FAILED " + failure);
            finishedCallback.run();
          });
    }
  }

  boolean finished() {
    return finished;
  }

  private void stop() {
    if (!stopping.compareAndSet(false, true)) return;
    armed = false;
    current.compareAndSet(this, null);
    if (ownsConnection()) {
      NativeSender.volume(0);
      BridgeState.volume = 0;
      BridgeState.publish();
    }
    if (recording == null) {
      finished = true;
      main.post(finishedCallback);
    }
  }

  void dump(java.io.PrintWriter writer, boolean includePcm) {
    if (!finished) {
      writer.println("ACOUSTIC_PENDING");
      return;
    }
    try {
      JSONObject result =
          new JSONObject()
              .put("schema", 1)
              .put("microphoneRate", MIC_RATE)
              .put("source", source)
              .put("sampleCount", sampleCount)
              .put("startedAtNs", startedAt)
              .put("toneFrames", AcousticSignal.TONE_FRAMES)
              .put("outputRate", AcousticSignal.RATE)
              .put("phoneSpeakerSignal", false)
              .put("reference", "sender PCM enqueue, not source capture")
              .put("adcInternalDelayCalibrated", false)
              .put("timebase", "MONOTONIC");
      if (failure != null) result.put("failure", failure);
      JSONArray stamps = new JSONArray(), tones = new JSONArray();
      for (long[] anchor : anchors) stamps.put(new JSONArray(anchor));
      synchronized (markers) {
        for (int i = 0; i < AcousticSignal.COUNT; i++)
          tones.put(
              new JSONObject()
                  .put("enqueuedAtNs", markers[i])
                  .put(
                      "senderPipeline",
                      markerStages[i] == null ? JSONObject.NULL : new JSONArray(markerStages[i]))
                  .put("phaseCode", AcousticSignal.PHASE_CODE[i])
                  .put("lowHz", AcousticSignal.LOW[i])
                  .put("highHz", AcousticSignal.HIGH[i]));
      }
      result.put("anchors", stamps).put("tones", tones);
      if (includePcm) {
        ByteBuffer pcm =
            ByteBuffer.allocate(sampleCount * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sampleCount; i++) pcm.putShort(samples[i]);
        result.put(
            "pcm16le",
            android.util.Base64.encodeToString(pcm.array(), android.util.Base64.NO_WRAP));
      }
      writer.println("ACOUSTIC_RECORD=" + result);
    } catch (Exception error) {
      writer.println("ACOUSTIC_EXPORT_FAILED " + error.getClass().getSimpleName());
    }
  }

  @Override
  public void close() {
    stop();
  }

  void clear() {
    clearRequested = true;
    stop();
    if (finished) Arrays.fill(samples, (short) 0);
  }
}
