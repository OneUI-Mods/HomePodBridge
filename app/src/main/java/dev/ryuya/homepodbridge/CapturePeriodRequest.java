package dev.ryuya.homepodbridge;

import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.IBinder;
import android.os.Parcel;
import de.robv.android.xposed.XposedHelpers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;

/** Optional stream-local request. Never sends a global (handle zero) AudioSystem parameter. */
final class CapturePeriodRequest {
  private static final String DESCRIPTOR = "android.media.IAudioFlingerService";
  // Transaction layout verified against this OS client implementation, not assumed from API level.
  private static final String CLIENT_SHA256 =
      "78065bc3c0476db5c2d7d29684c021f806347edcbae0b47ad9f5e7b211617d74";

  static void request(AudioRecord record, Object mix, int frames) {
    boolean running = false;
    try {
      byte[] client = Files.readAllBytes(Path.of("/system/lib64/audioflinger-aidl-cpp.so"));
      StringBuilder digest = new StringBuilder();
      for (byte b : MessageDigest.getInstance("SHA-256").digest(client))
        digest.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
      if (!CLIENT_SHA256.contentEquals(digest) || frames < 16 || frames > 256) return;
      String registration = (String) XposedHelpers.callMethod(mix, "getRegistration");
      if (registration == null || registration.isEmpty()) return;
      IBinder flinger =
          (IBinder)
              XposedHelpers.callStaticMethod(
                  XposedHelpers.findClass("android.os.ServiceManager", null),
                  "getService",
                  "media.audio_flinger");
      if (flinger == null || !DESCRIPTOR.equals(flinger.getInterfaceDescriptor())) return;
      record.startRecording();
      running = true;
      int input = -1;
      for (int attempt = 0; attempt < 5 && input <= 0; attempt++) {
        input = ownInput(registration);
        if (input <= 0) Thread.sleep(10);
      }
      record.stop();
      running = false;
      if (input <= 0) {
        android.util.Log.i("HomePodBridgeCapture", "No uniquely owned input for period request");
        return;
      }
      long before = frameCount(flinger, input);
      if (before <= 0 || before > 44100) return;
      Parcel data = Parcel.obtain();
      Parcel reply = Parcel.obtain();
      try {
        data.writeInterfaceToken(DESCRIPTOR);
        data.writeInt(input);
        data.writeString("frame_count=" + frames);
        if (!flinger.transact(18, data, reply, 0)) return;
        reply.readException();
      } finally {
        data.recycle();
        reply.recycle();
      }
      long after = frameCount(flinger, input);
      android.util.Log.i(
          "HomePodBridgeCapture", "Owned input period frames before=" + before + " after=" + after);
    } catch (Throwable e) {
      android.util.Log.i(
          "HomePodBridgeCapture",
          "Owned input period request unavailable: " + e.getClass().getSimpleName());
    } finally {
      if (running) {
        try {
          record.stop();
        } catch (RuntimeException ignored) {
        }
      }
    }
  }

  private static long frameCount(IBinder binder, int input) throws android.os.RemoteException {
    if (input <= 0) return -1;
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(DESCRIPTOR);
      data.writeInt(input);
      if (!binder.transact(5, data, reply, 0)) return -1;
      reply.readException();
      return reply.readLong();
    } finally {
      data.recycle();
      reply.recycle();
    }
  }

  private static int ownInput(String registration) {
    var patches = new ArrayList<Object>();
    int result =
        (Integer) XposedHelpers.callStaticMethod(AudioManager.class, "listAudioPatches", patches);
    if (result != 0) return -1;
    int found = -1;
    for (Object patch : patches) {
      Object[] sources = (Object[]) XposedHelpers.callMethod(patch, "sources");
      Object[] sinks = (Object[]) XposedHelpers.callMethod(patch, "sinks");
      if (sources.length != 1 || sinks.length != 1) continue;
      Object source = XposedHelpers.callMethod(sources[0], "port");
      if (!source.getClass().getName().equals("android.media.AudioDevicePort")) continue;
      int type = (Integer) XposedHelpers.callMethod(source, "type");
      String address = (String) XposedHelpers.callMethod(source, "address");
      if (type != 0x80000100 || !registration.equals(address)) continue;
      Object sink = XposedHelpers.callMethod(sinks[0], "port");
      if (!sink.getClass().getName().equals("android.media.AudioMixPort")) continue;
      int handle = (Integer) XposedHelpers.callMethod(sink, "ioHandle");
      if (handle <= 0 || (found > 0 && found != handle)) return -1;
      found = handle;
    }
    return found;
  }

  private CapturePeriodRequest() {}
}
