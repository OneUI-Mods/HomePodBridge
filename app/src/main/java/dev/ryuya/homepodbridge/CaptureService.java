package dev.ryuya.homepodbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
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
import android.os.PowerManager;
import android.os.Process;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class CaptureService extends Service {
  static final String STOP = "dev.ryuya.homepodbridge.STOP";
  private final Handler main = new Handler(Looper.getMainLooper());
  // Service recreation must wait for the previous native sender and route lease.
  private static final ExecutorService worker = Executors.newSingleThreadExecutor();
  private static final ExecutorService devicePaths = Executors.newSingleThreadExecutor();
  // The shared worker must not open another path after an unconfirmed dedicated close.
  private static volatile boolean halCleanupUnconfirmed;

  static void blockUnconfirmedHalCleanup() {
    halCleanupUnconfirmed = true;
  }

  private final AtomicInteger generation = new AtomicInteger();
  private MediaProjection projection;
  private int musicUid;
  private boolean systemMode;
  private IBinder lifetime = new Binder();
  private volatile ParcelFileDescriptor systemPipe;
  private volatile OwnedAudioPath activeDevicePath;
  private volatile boolean deviceBufferSuppressed;
  private boolean destroyed;
  private volatile boolean zeroProbeActive;
  private volatile long zeroProbeFrames;
  private volatile boolean dedicatedHalProbe;
  private volatile RootHalInput activeZeroHal;
  private volatile long halPendingFrames = -1;
  private volatile String halNonce;
  private ConnectionCallback pendingCallback;
  private int callbackGeneration;
  private String selectedPackage = BridgeState.MUSIC;
  private WholeDeviceVolume wholeDeviceVolume;

  boolean diagnosticAudio() {
    return zeroProbeActive;
  }

  IBinder lifetime() {
    return lifetime;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    wholeDeviceVolume = new WholeDeviceVolume(this, main);
  }

  void reconfigure() {
    if (BridgeState.selected == null) return;
    if (HomePodRoutes.restartCurrent(selectedPackage)) return;
    Receiver receiver = BridgeState.selected;
    if (HomePodRoutes.available()) HomePodRoutes.selectFromSystem(selectedPackage, receiver.id());
    else select(receiver, selectedPackage, success -> {});
  }

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
      if (!StreamSettings.load(this).wholeDevice())
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
    select(receiver, sourcePackage, callback, false);
  }

  void selectZeroProbe(Receiver receiver, ConnectionCallback callback) {
    select(receiver, BridgeState.MUSIC, callback, true);
  }

  void selectZeroHalProbe(Receiver receiver, ConnectionCallback callback) {
    selectZeroHalProbe(receiver, callback, StreamSettings.load(this).wholeDevice(), false);
  }

  void selectZeroHalProbe(
      Receiver receiver, ConnectionCallback callback, boolean whole, boolean pulse) {
    select(receiver, BridgeState.MUSIC, callback, true, true, whole, pulse);
  }

  private void select(
      Receiver receiver, String sourcePackage, ConnectionCallback callback, boolean zeroProbe) {
    select(receiver, sourcePackage, callback, zeroProbe, false, false, false);
  }

  private void select(
      Receiver receiver,
      String sourcePackage,
      ConnectionCallback callback,
      boolean zeroProbe,
      boolean dedicatedHal,
      boolean testWhole,
      boolean pulse) {
    if (dedicatedHal && (!zeroProbe || !systemMode)) {
      callback.finished(false);
      return;
    }
    if (destroyed || (projection == null && !systemMode)) {
      callback.finished(false);
      return;
    }
    if (!systemMode
        && !StreamSettings.load(this).wholeDevice()
        && !BridgeState.MUSIC.equals(sourcePackage)) {
      callback.finished(false);
      return;
    }
    cancelPending();
    selectedPackage = sourcePackage;
    int token = generation.incrementAndGet();
    closeSystemInput();
    lifetime = new Binder();
    pendingCallback = callback;
    callbackGeneration = token;
    BridgeState.selected = receiver;
    BridgeState.connected = false;
    BridgeState.volume = 0;
    BridgeState.timing = null;
    BridgeState.pipeline = null;
    BridgeState.activePhoneTiming = null;
    BridgeState.phonePath = null;
    zeroProbeActive = zeroProbe;
    zeroProbeFrames = 0;
    dedicatedHalProbe = dedicatedHal;
    halPendingFrames = -1;
    halNonce = null;
    BridgeState.status = R.string.connecting;
    BridgeState.publish();
    IBinder connectionLifetime = lifetime;
    worker.execute(
        () ->
            stream(
                receiver,
                sourcePackage,
                token,
                connectionLifetime,
                zeroProbe,
                dedicatedHal,
                testWhole,
                pulse));
  }

  private void stream(
      Receiver receiver,
      String sourcePackage,
      int token,
      IBinder connectionLifetime,
      boolean zeroProbe,
      boolean forceDedicated,
      boolean testWhole,
      boolean pulse) {
    AudioRecord recorder = null;
    RootHalInput zeroHal = null;
    ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
    Network previousNetwork = connectivity.getBoundNetworkForProcess();
    boolean networkBound = false;
    LocalRoute localRoute = null;
    OwnedAudioPath devicePath = null;
    boolean started = false;
    long capturedFrames = 0;
    long signalBlocks = 0;
    StreamSettings savedSettings = StreamSettings.load(this);
    StreamSettings settings =
        forceDedicated
            ? savedSettings.withWholeDevice(testWhole)
            : zeroProbe ? savedSettings.withWholeDevice(true) : savedSettings;
    boolean dedicatedHal =
        systemMode && (forceDedicated || (!zeroProbe && settings.effectiveDedicatedCapture()));
    setBackend(
        token,
        dedicatedHal ? BridgeState.CaptureBackend.CHECKING : BridgeState.CaptureBackend.STANDARD);
    PowerManager.WakeLock wake =
        getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomePodBridge:stream");
    wake.setReferenceCounted(false);
    int failure = R.string.connection_failed;
    try {
      if (generation.get() != token) return;
      if (halCleanupUnconfirmed)
        throw new java.io.IOException("Previous capture cleanup unconfirmed");
      wake.acquire(60_000);
      long nextWakeRefresh = android.os.SystemClock.elapsedRealtime() + 30_000;
      if (!systemMode
          && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
              != PackageManager.PERMISSION_GRANTED) {
        throw new SecurityException("Audio permission revoked");
      }
      try {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
      } catch (RuntimeException ignored) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
      }
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
      if (!networkBound) localRoute = LocalRoute.acquire(receiver.host());
      PcmPipe rootInput = null;
      if (dedicatedHal && settings.wholeDevice()) {
        try {
          if (!RootConnector.openExternal(connectionLifetime, true)) dedicatedHal = false;
        } catch (android.os.RemoteException | RuntimeException unavailable) {
          RootConnector.close(connectionLifetime);
          dedicatedHal = false;
        }
        if (!dedicatedHal) setBackend(token, BridgeState.CaptureBackend.FALLBACK);
      }
      if (dedicatedHal) {
        try {
          zeroHal =
              new RootHalInput(
                  this,
                  sourcePackage,
                  settings.wholeDevice(),
                  zeroProbe,
                  pulse,
                  settings.effectiveShortMixerPeriod() ? settings.effectivePhoneTiming() : null);
          activeZeroHal = zeroHal;
          halNonce = zeroHal.nonce;
          rootInput =
              new PcmPipe(
                  java.nio.channels.Channels.newChannel(zeroHal.input()),
                  true,
                  System::nanoTime,
                  PcmPipe.HAL_MAGIC);
          BridgeState.captureRegistration = zeroHal.registration;
          main.post(
              () -> {
                if (!destroyed && generation.get() == token)
                  BridgeState.activePhoneTiming =
                      settings.effectiveShortMixerPeriod() ? settings.effectivePhoneTiming() : null;
              });
          setBackend(
              token,
              zeroProbe
                  ? BridgeState.CaptureBackend.DEDICATED_TEST
                  : settings.effectiveShortMixerPeriod()
                      ? BridgeState.CaptureBackend.DEDICATED_SHORT
                      : BridgeState.CaptureBackend.DEDICATED);
          if (generation.get() != token) return;
        } catch (RootHalInput.StartupException unavailable) {
          if (!unavailable.safeFallback) {
            halCleanupUnconfirmed = true;
            throw unavailable;
          }
          RootConnector.close(connectionLifetime);
          dedicatedHal = false;
          setBackend(token, BridgeState.CaptureBackend.FALLBACK);
        }
      }
      if (generation.get() != token) return;
      if (systemMode && !dedicatedHal) {
        RootConnector.Input opened =
            RootConnector.open(sourcePackage, connectionLifetime, settings, zeroProbe);
        ParcelFileDescriptor pipe = opened.pipe();
        systemPipe = pipe;
        BridgeState.captureRegistration = opened.registration();
        if (generation.get() != token) {
          closeSystemInput();
          return;
        }
        var input = new ParcelFileDescriptor.AutoCloseInputStream(pipe);
        rootInput = new PcmPipe(input.getChannel(), opened.timed());
        if (settings.effectiveDeviceBuffer()
            && !deviceBufferSuppressed
            && opened.registration() != null) {
          Context app = getApplicationContext();
          devicePath =
              new OwnedAudioPath(
                  operation -> HalInspection.operateRoute(app, opened.registration(), operation),
                  devicePaths,
                  update ->
                      main.post(
                          () -> {
                            if (destroyed || generation.get() != token) return;
                            BridgeState.phonePath = update;
                            BridgeState.publishTiming();
                            if (update.state() == OwnedAudioPath.State.RECOVERY_REQUIRED) {
                              deviceBufferSuppressed = true;
                              reconfigure();
                            }
                          }),
                  settings.deviceBufferFrames());
          activeDevicePath = devicePath;
          devicePath.start();
        } else if (settings.effectiveDeviceBuffer()) {
          main.post(
              () -> {
                if (!destroyed && generation.get() == token) {
                  BridgeState.phonePath =
                      new OwnedAudioPath.Update(
                          OwnedAudioPath.State.STANDARD,
                          "Optional device buffering unavailable; existing capture retained");
                  BridgeState.publishTiming();
                }
              });
        }
      } else if (!systemMode) {
        AudioPlaybackCaptureConfiguration.Builder captureBuilder =
            new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA);
        if (settings.wholeDevice())
          captureBuilder
              .addMatchingUsage(AudioAttributes.USAGE_GAME)
              .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
        else captureBuilder.addMatchingUid(musicUid);
        AudioPlaybackCaptureConfiguration capture = captureBuilder.build();
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
                .setBufferSizeInBytes(Math.max(settings.samplesPerRead() * 2, minimum))
                .build();
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
          throw new IllegalStateException("Recorder unavailable");
        recorder.startRecording();
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
          throw new IllegalStateException("Capture unavailable");
      }
      NativeSender.start(
          receiver.host(),
          receiver.port(),
          receiver.name(),
          receiver.deviceId(),
          identity(),
          BuildConfig.VOLUME_LIMIT,
          settings.effectiveBufferMs(),
          settings.requestedPresentationMs(),
          settings.receiverTiming().independent(),
          settings.requestedReceiverMinimumMs(),
          settings.requestedReceiverMaximumMs(),
          settings.packetFramesForBackend(dedicatedHal) == 88,
          settings.compressedAudio(),
          settings.effectiveAdaptiveBuffer());
      ByteBuffer pcm =
          ByteBuffer.allocateDirect(settings.samplesPerRead() * 2).order(ByteOrder.LITTLE_ENDIAN);
      long deadline = System.nanoTime() + 20_000_000_000L;
      long nextTiming = 0;
      while (generation.get() == token) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now >= nextWakeRefresh) {
          wake.acquire(60_000);
          nextWakeRefresh = now + 30_000;
        }
        int state = NativeSender.state();
        if (state < 0 || (!started && System.nanoTime() > deadline)) break;
        if (state == 2 && now >= nextTiming) {
          int[] timing = NativeSender.timing();
          if (timing != null && timing.length >= 4 && timing[1] > 0) {
            PipelineTiming pipeline =
                new PipelineTiming(
                    systemMode && !dedicatedHal
                        ? RootConnector.captureTiming(connectionLifetime)
                        : null,
                    NativeSender.pipeline(),
                    rootInput == null ? -1 : rootInput.transitMicros(),
                    System.nanoTime(),
                    dedicatedHal ? rootInput.pendingFrames() : -1);
            main.post(
                () -> {
                  if (generation.get() != token || destroyed) return;
                  BridgeState.timing = timing;
                  BridgeState.pipeline = pipeline;
                  BridgeState.publishTiming();
                });
          }
          nextTiming = now + 2000;
        }
        if (!started && state == 2) {
          started = true;
          main.post(
              () -> {
                if (generation.get() != token || destroyed) return;
                BridgeState.connected = true;
                deleteFile("last-capture-error.txt");
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
                : recorder.read(pcm, pcm.capacity(), AudioRecord.READ_NON_BLOCKING);
        if (dedicatedHal) halPendingFrames = rootInput.pendingFrames();
        if (read < 0) {
          failure = R.string.capture_failed;
          break;
        }
        if (read > 0 && state == 2) {
          if (read % 4 != 0) throw new java.io.IOException("Truncated stereo frame");
          capturedFrames += read / 4;
          for (int i = 0; i < read; i += 2) {
            if (pcm.getShort(i) != 0) {
              signalBlocks++;
              break;
            }
          }
          AcousticProbe acoustic = zeroProbe ? AcousticProbe.forLifetime(connectionLifetime) : null;
          if (zeroProbe) {
            ZeroProbePcm.clear(pcm, read);
            if (acoustic != null) acoustic.render(pcm, read);
            zeroProbeFrames += read / 4;
          }
          while (!NativeSender.write(pcm, read)
              && generation.get() == token
              && NativeSender.state() == 2) Thread.sleep(2);
          if (acoustic != null && generation.get() == token && NativeSender.state() == 2)
            acoustic.accepted();
        }
        if (read == 0) Thread.sleep(settings.lowLatency() ? 1 : 5);
      }
    } catch (Exception e) {
      if (!destroyed && generation.get() == token) {
        try (var error = openFileOutput("last-capture-error.txt", MODE_PRIVATE)) {
          error.write(
              (e.getClass().getSimpleName() + ": " + e.getMessage())
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) {
        }
      }
      failure = R.string.capture_failed;
    } finally {
      if (zeroHal != null) {
        zeroHal.close();
        if (activeZeroHal == zeroHal) activeZeroHal = null;
      }
      try {
        try (var diagnostics = openFileOutput("last-connection.txt", MODE_PRIVATE)) {
          diagnostics.write(
              NativeSender.diagnostic().getBytes(java.nio.charset.StandardCharsets.UTF_8));
          diagnostics.write(
              ("\ncapturedFrames="
                      + capturedFrames
                      + " signalBlocks="
                      + signalBlocks
                      + " wholeDevice="
                      + settings.wholeDevice()
                      + " bufferMs="
                      + settings.effectiveBufferMs()
                      + " blockMs="
                      + settings.effectiveBlockMs()
                      + " latencyMs="
                      + settings.requestedPresentationMs()
                      + " receiverMinimumMs="
                      + settings.requestedReceiverMinimumMs()
                      + " receiverMaximumMs="
                      + settings.requestedReceiverMaximumMs()
                      + " independentTiming="
                      + settings.receiverTiming().independent()
                      + " profile="
                      + settings.profile()
                      + " compressedAudio="
                      + settings.compressedAudio()
                      + " adaptiveBuffer="
                      + settings.effectiveAdaptiveBuffer()
                      + "\n")
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) {
        }
        if (devicePath != null) devicePath.close();
        closeSystemInput();
        if (recorder != null) {
          try {
            recorder.stop();
          } catch (IllegalStateException ignored) {
          }
          recorder.release();
        }
        NativeSender.stop();
        if (zeroHal != null) halCleanupUnconfirmed = !zeroHal.awaitCleanup(5000);
        if (localRoute != null) localRoute.close();
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
      } finally {
        if (wake.isHeld()) wake.release();
      }
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
    RootHalInput zeroHal = activeZeroHal;
    activeZeroHal = null;
    if (zeroHal != null) zeroHal.close();
    BridgeState.captureRegistration = null;
    BridgeState.phonePath = null;
    OwnedAudioPath devicePath = activeDevicePath;
    activeDevicePath = null;
    if (devicePath != null) devicePath.close();
    ParcelFileDescriptor pipe = systemPipe;
    systemPipe = null;
    if (pipe == null) {
      RootConnector.close(lifetime);
      return;
    }
    try {
      pipe.close();
    } catch (java.io.IOException ignored) {
    }
    RootConnector.close(lifetime);
  }

  private void setBackend(int token, BridgeState.CaptureBackend backend) {
    main.post(
        () -> {
          if (!destroyed && generation.get() == token) {
            BridgeState.captureBackend = backend;
            BridgeState.publishTiming();
          }
        });
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
    if (wholeDeviceVolume != null) wholeDeviceVolume.close();
    destroyed = true;
    cancelPending();
    generation.incrementAndGet();
    closeSystemInput();
    if (projection != null) {
      projection.unregisterCallback(projectionCallback);
      projection.stop();
      projection = null;
    }
    BridgeState.capture = null;
    BridgeState.selected = null;
    BridgeState.connected = false;
    BridgeState.pipeline = null;
    BridgeState.activePhoneTiming = null;
    BridgeState.status = R.string.idle;
    BridgeState.publish();
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
    writer.println("HomePod Bridge sender diagnostics (no recorded PCM)");
    writer.println(
        "connected="
            + BridgeState.connected
            + " volume="
            + BridgeState.volume
            + " wholeDevice="
            + StreamSettings.load(this).wholeDevice());
    writer.println(NativeSender.diagnostic());
    writer.println(
        "captureBackend="
            + BridgeState.captureBackend
            + " dedicatedRequested="
            + StreamSettings.load(this).effectiveDedicatedCapture());
    writer.println(
        "dedicatedHalProbe="
            + dedicatedHalProbe
            + " halQueuedFrames="
            + halPendingFrames
            + " halNonce="
            + halNonce
            + " captureScope="
            + (dedicatedHalProbe ? "self-root-zero" : "configured"));
    writer.println(
        "zeroProbe="
            + zeroProbeActive
            + " zeroProbeFrames="
            + zeroProbeFrames
            + " effectiveWholeDevice="
            + (zeroProbeActive || StreamSettings.load(this).wholeDevice()));
    OwnedAudioPath.Update path = BridgeState.phonePath;
    writer.println(
        "deviceBufferRequested="
            + StreamSettings.load(this).effectiveDeviceBuffer()
            + " deviceBufferSuppressed="
            + deviceBufferSuppressed
            + " phonePath="
            + (path == null ? "OFF" : path.state()));
    if (path != null) writer.println(path.report());
    PipelineTiming pipeline = BridgeState.pipeline;
    if (pipeline != null) {
      writer.println("captureTiming=" + java.util.Arrays.toString(pipeline.capture()));
      writer.println("pipeTransitMicros=" + pipeline.pipeMicros());
    }
  }
}
