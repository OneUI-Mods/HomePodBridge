package dev.ryuya.homepodbridge;

import android.content.Context;

record StreamSettings(
    boolean wholeDevice,
    int bufferMs,
    int blockMs,
    int latencyMs,
    boolean lowLatency,
    boolean highQuality,
    boolean compressedAudio) {
  StreamSettings(boolean wholeDevice, int bufferMs, int blockMs, int latencyMs) {
    this(wholeDevice, bufferMs, blockMs, latencyMs, false, false, false);
  }

  StreamSettings(
      boolean wholeDevice, int bufferMs, int blockMs, int latencyMs, boolean lowLatency) {
    this(wholeDevice, bufferMs, blockMs, latencyMs, lowLatency, false, false);
  }

  StreamSettings {
    bufferMs = Math.max(0, Math.min(300, bufferMs));
    blockMs = Math.max(5, Math.min(40, blockMs));
    latencyMs = Math.max(20, Math.min(2000, latencyMs));
    if (lowLatency) highQuality = false;
  }

  static StreamSettings load(Context context) {
    var preferences = context.getSharedPreferences("stream", Context.MODE_PRIVATE);
    return new StreamSettings(
        preferences.getBoolean("wholeDevice", false),
        preferences.getInt("bufferMs", 150),
        preferences.getInt("blockMs", 10),
        preferences.getInt("latencyMs", 1500),
        preferences.getBoolean("lowLatency", false),
        preferences.getBoolean("highQuality", false),
        preferences.getBoolean("compressedAudio", false));
  }

  void save(Context context) {
    context
        .getSharedPreferences("stream", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("wholeDevice", wholeDevice)
        .putInt("bufferMs", bufferMs)
        .putInt("blockMs", blockMs)
        .putInt("latencyMs", latencyMs)
        .putBoolean("lowLatency", lowLatency)
        .putBoolean("highQuality", highQuality)
        .putBoolean("compressedAudio", compressedAudio)
        .apply();
  }

  StreamSettings withWholeDevice(boolean enabled) {
    return new StreamSettings(
        enabled, bufferMs, blockMs, latencyMs, lowLatency, highQuality, compressedAudio);
  }

  StreamSettings withProfile(int profile) {
    return new StreamSettings(
        wholeDevice, bufferMs, blockMs, latencyMs, profile == 1, profile == 2, compressedAudio);
  }

  StreamSettings withCompression(boolean enabled) {
    return new StreamSettings(
        wholeDevice, bufferMs, blockMs, latencyMs, lowLatency, highQuality, enabled);
  }

  StreamSettings withBuffers(int buffer, int block, int latency) {
    return new StreamSettings(wholeDevice, buffer, block, latency, false, false, compressedAudio);
  }

  int profile() {
    return lowLatency ? 1 : highQuality ? 2 : 0;
  }

  int samplesPerRead() {
    return 2 * (44100 * effectiveBlockMs() / 1000);
  }

  int effectiveBufferMs() {
    return lowLatency ? 0 : highQuality ? 150 : bufferMs;
  }

  int effectiveBlockMs() {
    return lowLatency ? 5 : highQuality ? 10 : blockMs;
  }

  int effectiveLatencyMs() {
    return lowLatency ? 50 : highQuality ? 1500 : latencyMs;
  }
}
