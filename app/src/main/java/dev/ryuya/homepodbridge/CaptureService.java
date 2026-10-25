package dev.ryuya.homepodbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class CaptureService extends Service {
  static final String STOP = "dev.ryuya.homepodbridge.STOP";
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicInteger generation = new AtomicInteger();
  private MediaProjection projection;
  private int musicUid;
  private boolean systemMode;
  private final IBinder lifetime = new Binder();
  private volatile ParcelFileDescriptor systemPipe;
  private boolean destroyed;
  private ConnectionCallback pendingCallback;
  private int callbackGeneration;
  private final MediaProjection.Callback projectionCallback =
      new MediaProjection.Callback() {
        @Override
        public void onStop() {
          stopSelf();
        }
      };

  interface ConnectionCallback {
    void finished(boolean success);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || STOP.equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (projection != null || systemMode) return START_NOT_STICKY;
    if (intent.getBooleanExtra("system", false) && RootConnector.available()) {
      startForeground(
          1,
          notification(getString(R.string.sharing)),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
      systemMode = true;
      BridgeState.capture = this;
      BridgeState.status = R.string.sharing;
      BridgeState.publish();
      return START_NOT_STICKY;
    }
    Intent consent = intent.getParcelableExtra("consent", Intent.class);
    if (consent == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    try {
      startForeground(
          1,
          notification(getString(R.string.sharing)),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
      musicUid = getPackageManager().getApplicationInfo(BridgeState.MUSIC, 0).uid;
      projection =
          getSystemService(MediaProjectionManager.class)
              .getMediaProjection(intent.getIntExtra("result", 0), consent);
      if (projection == null) throw new IllegalStateException("Projection not available");
      projection.registerCallback(projectionCallback, main);
      BridgeState.capture = this;
      BridgeState.status = R.string.sharing;
      BridgeState.publish();
    } catch (Exception e) {
      BridgeState.status = R.string.capture_failed;
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  void select(Receiver receiver, ConnectionCallback callback) {
    select(receiver, BridgeState.MUSIC, callback);
  }

  void select(Receiver receiver, String sourcePackage, ConnectionCallback callback) {
    if (destroyed || (projection == null && !systemMode)) {
      callback.finished(false);
      return;
    }
    if (!systemMode && !BridgeState.MUSIC.equals(sourcePackage)) {
      callback.finished(false);
      return;
    }
    cancelPending();
    int token = generation.incrementAndGet();
    closeSystemInput();
    pendingCallback = callback;
    callbackGeneration = token;
    BridgeState.selected = receiver;
    BridgeState.connected = false;
    BridgeState.status = R.string.connecting;
    BridgeState.publish();
    worker.execute(() -> stream(receiver, sourcePackage, token));
  }

  private void stream(Receiver receiver, String sourcePackage, int token) {
    AudioRecord recorder = null;
    ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
    Network previousNetwork = connectivity.getBoundNetworkForProcess();
    boolean networkBound = false;
    boolean started = false;
    int failure = R.string.connection_failed;
    try {
      if (generation.get() != token) return;
      if (!systemMode
          && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
              != PackageManager.PERMISSION_GRANTED) {
        throw new SecurityException("Audio permission revoked");
      }
      Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
      for (Network network : connectivity.getAllNetworks()) {
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        if (capabilities != null
            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
          networkBound = connectivity.bindProcessToNetwork(network);
          if (networkBound) break;
        }
      }
      PcmPipe rootInput = null;
      if (systemMode) {
        ParcelFileDescriptor pipe = RootConnector.open(sourcePackage, lifetime);
        systemPipe = pipe;
        if (generation.get() != token) {
          closeSystemInput();
          return;
        }
        InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe);
        rootInput = new PcmPipe(input, 882);
      } else {
        AudioPlaybackCaptureConfiguration capture =
            new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUid(musicUid)
                .build();
        AudioFormat format =
            new AudioFormat.Builder()
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        int minimum =
            AudioRecord.getMinBufferSize(
                44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        recorder =
            new AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(capture)
                .setBufferSizeInBytes(Math.max(16384, minimum * 2))
                .build();
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
          throw new IllegalStateException("Recorder unavailable");
        recorder.startRecording();
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
          throw new IllegalStateException("Capture unavailable");
      }
      NativeSender.start(
          receiver.host(), receiver.port(), receiver.name(), receiver.deviceId(), identity());
      short[] pcm = new short[882];
      long deadline = System.nanoTime() + 20_000_000_000L;
      while (generation.get() == token) {
        int state = NativeSender.state();
        if (state < 0 || (!started && System.nanoTime() > deadline)) break;
        if (!started && state == 2) {
          started = true;
          main.post(
              () -> {
                if (generation.get() != token || destroyed) return;
                BridgeState.connected = true;
                BridgeState.status = R.string.playing;
                BridgeState.publish();
                getSystemService(NotificationManager.class)
                    .notify(1, notification(getString(R.string.playing, receiver.name())));
                complete(token, true);
              });
        }
        int read =
            rootInput != null
                ? rootInput.read(pcm)
                : recorder.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
        if (read < 0) {
          failure = R.string.capture_failed;
          break;
        }
        if (read > 0 && state == 2) NativeSender.write(pcm, read - read % 2);
        if (read == 0) Thread.sleep(5);
      }
    } catch (Exception e) {
      try (var error = openFileOutput("last-capture-error.txt", MODE_PRIVATE)) {
        error.write((e.getClass().getSimpleName() + ": " + e.getMessage()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      } catch (java.io.IOException ignored) {}
      failure = R.string.capture_failed;
    } finally {
      try (var diagnostics = openFileOutput("last-connection.txt", MODE_PRIVATE)) {
        diagnostics.write(
            NativeSender.diagnostic().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      } catch (java.io.IOException ignored) {
      }
      closeSystemInput();
      if (recorder != null) {
        try {
          recorder.stop();
        } catch (IllegalStateException ignored) {
        }
        recorder.release();
      }
      NativeSender.stop();
      if (networkBound) connectivity.bindProcessToNetwork(previousNetwork);
      boolean wasStarted = started;
      int message = failure;
      main.post(
          () -> {
            if (destroyed || generation.get() != token) return;
            BridgeState.connected = false;
            BridgeState.selected = null;
            BridgeState.status = message;
            BridgeState.publish();
            if (!wasStarted) complete(token, false);
          });
    }
  }

  void disconnect() {
    cancelPending();
    generation.incrementAndGet();
    closeSystemInput();
    BridgeState.selected = null;
    BridgeState.connected = false;
    BridgeState.status = R.string.sharing;
    BridgeState.publish();
    getSystemService(NotificationManager.class)
        .notify(1, notification(getString(R.string.sharing)));
  }

  private void closeSystemInput() {
    ParcelFileDescriptor pipe = systemPipe;
    systemPipe = null;
    if (pipe == null) return;
    try {
      pipe.close();
    } catch (java.io.IOException ignored) {
    }
    RootConnector.close(lifetime);
  }

  private void complete(int token, boolean success) {
    if (pendingCallback == null || callbackGeneration != token) return;
    ConnectionCallback callback = pendingCallback;
    pendingCallback = null;
    callback.finished(success);
  }

  private void cancelPending() {
    if (pendingCallback != null) complete(callbackGeneration, false);
  }

  private String identity() {
    var preferences = getSharedPreferences("identity", MODE_PRIVATE);
    String value = preferences.getString("device", null);
    if (value == null) {
      String random =
          UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
      value =
          "02:"
              + random.substring(2, 4)
              + ":"
              + random.substring(4, 6)
              + ":"
              + random.substring(6, 8)
              + ":"
              + random.substring(8, 10)
              + ":"
              + random.substring(10, 12);
      preferences.edit().putString("device", value).apply();
    }
    return value;
  }

  private Notification notification(String text) {
    NotificationManager manager = getSystemService(NotificationManager.class);
    NotificationChannel channel =
        new NotificationChannel(
            "sharing", getString(R.string.channel), NotificationManager.IMPORTANCE_LOW);
    channel.setDescription(getString(R.string.channel_description));
    manager.createNotificationChannel(channel);
    PendingIntent open =
        PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, CaptureService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, "sharing")
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_speaker)
        .setContentIntent(open)
        .setOngoing(true)
        .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build())
        .build();
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    cancelPending();
    generation.incrementAndGet();
    closeSystemInput();
    if (projection != null) {
      projection.unregisterCallback(projectionCallback);
      projection.stop();
      projection = null;
    }
    worker.shutdown();
    BridgeState.capture = null;
    BridgeState.selected = null;
    BridgeState.connected = false;
    BridgeState.status = R.string.idle;
    BridgeState.publish();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
