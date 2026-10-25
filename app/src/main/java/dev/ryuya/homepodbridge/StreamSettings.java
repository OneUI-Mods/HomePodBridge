package dev.ryuya.homepodbridge;

import android.content.Context;

record StreamSettings(boolean wholeDevice, int bufferMs, int blockMs, int latencyMs) {
  StreamSettings {
    bufferMs = Math.max(0, Math.min(300, bufferMs));
    blockMs = Math.max(5, Math.min(40, blockMs));
    latencyMs = Math.max(250, Math.min(2000, latencyMs));
  }

  static StreamSettings load(Context context) {
    var preferences = context.getSharedPreferences("stream", Context.MODE_PRIVATE);
    return new StreamSettings(
        preferences.getBoolean("wholeDevice", false),
        preferences.getInt("bufferMs", 150),
        preferences.getInt("blockMs", 10),
        preferences.getInt("latencyMs", 1500));
  }

  void save(Context context) {
    context
        .getSharedPreferences("stream", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("wholeDevice", wholeDevice)
        .putInt("bufferMs", bufferMs)
        .putInt("blockMs", blockMs)
        .putInt("latencyMs", latencyMs)
        .apply();
  }

  int samplesPerRead() {
    return 2 * (44100 * blockMs / 1000);
  }
}
