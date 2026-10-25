package dev.ryuya.homepodbridge;

import android.media.AudioRecord;
import de.robv.android.xposed.XposedHelpers;

/** Optional, per-recorder request before recording starts. No global AudioSystem parameters. */
final class RootCaptureTuner {
  private static boolean loaded;

  static synchronized int request(AudioRecord record, int frames, String modulePath) {
    try {
      if (!loaded) {
        String abi = android.os.Build.SUPPORTED_64_BIT_ABIS[0];
        System.load(modulePath + "!/lib/" + abi + "/libhomepod_capture.so");
        loaded = true;
      }
      long handle = XposedHelpers.getLongField(record, "mNativeAudioRecordHandle");
      return requestFrames(handle, frames);
    } catch (Throwable ignored) {
      return -1000;
    }
  }

  private static native int requestFrames(long recorder, int frames);

  private RootCaptureTuner() {}
}
