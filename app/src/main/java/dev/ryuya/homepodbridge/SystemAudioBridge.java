package dev.ryuya.homepodbridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import de.robv.android.xposed.XposedHelpers;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs only inside SystemUI. Captures USAGE_MEDIA for one package, never a microphone. */
final class SystemAudioBridge extends Binder {
  private final Context context;
  private final Signature[] moduleSignatures;
  private Stream stream;

  SystemAudioBridge(Context context, String modulePath) {
    this.context = context;
    var archive =
        context
            .getPackageManager()
            .getPackageArchiveInfo(modulePath, PackageManager.GET_SIGNING_CERTIFICATES);
    if (archive == null || archive.signingInfo == null)
      throw new IllegalStateException("Module signature unavailable");
    moduleSignatures = archive.signingInfo.getApkContentsSigners();
    attachInterface(null, RootProtocol.DESCRIPTOR);
  }

  @Override
  protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags)
      throws RemoteException {
    if (code == INTERFACE_TRANSACTION) {
      reply.writeString(RootProtocol.DESCRIPTOR);
      return true;
    }
    if (code != RootProtocol.OPEN
        && code != RootProtocol.OPEN_CONFIGURED
        && code != RootProtocol.VOLUME_ENDPOINT
        && code != RootProtocol.CLOSE) return super.onTransact(code, data, reply, flags);
    data.enforceInterface(RootProtocol.DESCRIPTOR);
    try {
      var app = context.getPackageManager().getApplicationInfo(RootProtocol.PACKAGE, 0);
      var installed =
          context
              .getPackageManager()
              .getPackageInfo(RootProtocol.PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
      if (Binder.getCallingUid() != app.uid
          || installed.signingInfo == null
          || !java.util.Arrays.equals(
              moduleSignatures, installed.signingInfo.getApkContentsSigners()))
        throw new SecurityException("Caller is not the installed module");
    } catch (PackageManager.NameNotFoundException e) {
      throw new SecurityException("Module not installed");
    }
    IBinder client = data.readStrongBinder();
    if (client == null) throw new IllegalArgumentException("Client lifetime token required");
    long identity = Binder.clearCallingIdentity();
    try {
      if (code == RootProtocol.VOLUME_ENDPOINT) {
        IBinder callback = data.readStrongBinder();
        if (stream != null
            && stream.client.equals(client)
            && stream.wholeDevice
            && callback != null) stream.volumeEndpoint(callback);
        reply.writeNoException();
        return true;
      }
      if (code == RootProtocol.CLOSE) {
        if (stream != null && stream.client.equals(client)) {
          stream.close();
          stream = null;
        }
        reply.writeNoException();
        return true;
      }
      String targetPackage = data.readString();
      if (targetPackage == null
          || targetPackage.isEmpty()
          || targetPackage.equals(RootProtocol.PACKAGE))
        throw new IllegalArgumentException("Invalid media package");
      boolean wholeDevice = code == RootProtocol.OPEN_CONFIGURED && data.readBoolean();
      int blockMs = code == RootProtocol.OPEN_CONFIGURED ? data.readInt() : 10;
      int targetUid =
          wholeDevice ? -1 : context.getPackageManager().getApplicationInfo(targetPackage, 0).uid;
      StreamSettings settings = new StreamSettings(wholeDevice, 150, blockMs, 1500);
      if (stream != null) {
        stream.close();
        stream = null;
      }
      ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
      try {
        stream = new Stream(client, targetUid, appUid(), pipe[1], settings);
        reply.writeNoException();
        pipe[0].writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
      } catch (Throwable e) {
        pipe[0].close();
        pipe[1].close();
        throw new IllegalStateException("System audio initialization failed", e);
      }
    } catch (Throwable e) {
      reply.writeException(new IllegalStateException("System audio capture is unavailable"));
    } finally {
      Binder.restoreCallingIdentity(identity);
    }
    return true;
  }

  private int appUid() throws PackageManager.NameNotFoundException {
    return context.getPackageManager().getApplicationInfo(RootProtocol.PACKAGE, 0).uid;
  }

  private final class Stream implements IBinder.DeathRecipient {
    final IBinder client;
    final Object policy;
    final AudioRecord record;
    final ParcelFileDescriptor writePipe;
    final AtomicBoolean closed = new AtomicBoolean();
    final int blockSamples;
    final boolean wholeDevice;
    private SystemVolumeEndpoint volumeEndpoint;

    Stream(
        IBinder client,
        int uid,
        int bridgeUid,
        ParcelFileDescriptor writePipe,
        StreamSettings settings)
        throws Exception {
      this.client = client;
      this.writePipe = writePipe;
      this.wholeDevice = settings.wholeDevice();
      blockSamples = settings.samplesPerRead();
      ClassLoader loader = context.getClassLoader();
      Object rule =
          XposedHelpers.newInstance(
              XposedHelpers.findClass("android.media.audiopolicy.AudioMixingRule$Builder", loader));
      if (settings.wholeDevice()) {
        XposedHelpers.callMethod(rule, "setTargetMixRole", 0); // MIX_ROLE_PLAYERS
        XposedHelpers.callMethod(rule, "excludeMixRule", 4, Integer.valueOf(bridgeUid));
        for (int usage :
            new int[] {
              AudioAttributes.USAGE_VOICE_COMMUNICATION,
              AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
            }) {
          XposedHelpers.callMethod(
              rule, "excludeRule", new AudioAttributes.Builder().setUsage(usage).build(), 1);
        }
      } else {
        XposedHelpers.callMethod(
            rule,
            "addRule",
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
            1);
        XposedHelpers.callMethod(rule, "addMixRule", 4, Integer.valueOf(uid)); // RULE_MATCH_UID
      }
      Object mix =
          XposedHelpers.newInstance(
              XposedHelpers.findClass("android.media.audiopolicy.AudioMix$Builder", loader),
              XposedHelpers.callMethod(rule, "build"));
      AudioFormat format =
          new AudioFormat.Builder()
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setSampleRate(44100)
              .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
              .build();
      XposedHelpers.callMethod(mix, "setFormat", format);
      XposedHelpers.callMethod(
          mix, "setRouteFlags", 2); // LOOP_BACK, without RENDER: redirect this media stream
      Object audioMix = XposedHelpers.callMethod(mix, "build");
      Object builder =
          XposedHelpers.newInstance(
              XposedHelpers.findClass("android.media.audiopolicy.AudioPolicy$Builder", loader),
              context);
      XposedHelpers.callMethod(builder, "setLooper", Looper.getMainLooper());
      XposedHelpers.callMethod(builder, "addMix", audioMix);
      policy = XposedHelpers.callMethod(builder, "build");
      AudioManager manager = context.getSystemService(AudioManager.class);
      int result = (Integer) XposedHelpers.callMethod(manager, "registerAudioPolicy", policy);
      if (result != 0)
        throw new IllegalStateException("Policy registration failed"); // AudioManager.SUCCESS
      AudioRecord created = null;
      try {
        created = (AudioRecord) XposedHelpers.callMethod(policy, "createAudioRecordSink", audioMix);
        if (created == null || created.getState() != AudioRecord.STATE_INITIALIZED)
          throw new IllegalStateException("Audio sink unavailable");
        record = created;
        client.linkToDeath(this, 0);
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
          throw new IllegalStateException("Recording failed");
      } catch (Throwable e) {
        if (created != null) created.release();
        try {
          client.unlinkToDeath(this, 0);
        } catch (RuntimeException ignored) {
        }
        try {
          XposedHelpers.callMethod(manager, "unregisterAudioPolicy", policy);
        } catch (Throwable ignored) {
        }
        throw new IllegalStateException("System audio initialization failed", e);
      }
      Thread thread = new Thread(this::pump, "HomePod-system-audio");
      thread.setDaemon(true);
      thread.start();
    }

    private void pump() {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
      try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(writePipe)) {
        short[] samples = new short[blockSamples];
        ByteBuffer bytes = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        while (!closed.get()) {
          int count = record.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
          if (count < 0) break;
          if (count == 0) continue;
          bytes.clear();
          for (int i = 0; i < count; i++) bytes.putShort(samples[i]);
          output.write(bytes.array(), 0, count * 2);
        }
      } catch (IOException | RuntimeException ignored) {
        // Pipe closure or process death ends this session without affecting SystemUI.
      } finally {
        close();
        record.release();
        try {
          XposedHelpers.callMethod(
              context.getSystemService(AudioManager.class), "unregisterAudioPolicy", policy);
        } catch (Throwable ignored) {
        }
        try {
          client.unlinkToDeath(this, 0);
        } catch (RuntimeException ignored) {
        }
      }
    }

    void close() {
      if (!closed.compareAndSet(false, true)) return;
      synchronized (this) {
        if (volumeEndpoint != null) {
          volumeEndpoint.close();
          volumeEndpoint = null;
        }
      }
      try {
        record.stop();
      } catch (RuntimeException ignored) {
      }
      try {
        writePipe.close();
      } catch (IOException ignored) {
      }
    }

    @Override
    public void binderDied() {
      close();
    }

    synchronized void volumeEndpoint(IBinder callback) {
      if (closed.get()) return;
      if (volumeEndpoint == null) {
        try {
          volumeEndpoint = new SystemVolumeEndpoint(context, callback);
          de.robv.android.xposed.XposedBridge.log("HomePod Bridge: system volume endpoint ready");
        } catch (Throwable e) {
          de.robv.android.xposed.XposedBridge.log(
              "HomePod Bridge: system volume endpoint unavailable ("
                  + e.getClass().getSimpleName()
                  + ")");
          de.robv.android.xposed.XposedBridge.log(e);
        }
      }
    }
  }
}
