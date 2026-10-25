package dev.ryuya.homepodbridge;

import android.content.Context;

record StreamSettings(
    boolean wholeDevice,
    int bufferMs,
    int blockMs,
    int latencyMs,
    boolean lowLatency,
    boolean highQuality,
    boolean compressedAudio,
    boolean adaptiveBuffer) {
  StreamSettings(boolean wholeDevice, int bufferMs, int blockMs, int latencyMs) {
    this(wholeDevice, bufferMs, blockMs, latencyMs, false, false, false, true);
  }

  StreamSettings(
      boolean wholeDevice, int bufferMs, int blockMs, int latencyMs, boolean lowLatency) {
    this(wholeDevice, bufferMs, blockMs, latencyMs, lowLatency, false, false, true);
  }

  StreamSettings {
    bufferMs = Math.max(0, Math.min(300, bufferMs));
    blockMs = Math.max(2, Math.min(40, blockMs));
    latencyMs = Math.max(10, Math.min(2000, latencyMs));
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
        preferences.getBoolean("compressedAudio", false),
        preferences.getBoolean("adaptiveBuffer", true));
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
        .putBoolean("adaptiveBuffer", adaptiveBuffer)
        .apply();
  }

  StreamSettings withWholeDevice(boolean enabled) {
    return new StreamSettings(
        enabled,
        bufferMs,
        blockMs,
        latencyMs,
        lowLatency,
        highQuality,
        compressedAudio,
        adaptiveBuffer);
  }

  StreamSettings withProfile(int profile) {
    return new StreamSettings(
        wholeDevice,
        bufferMs,
        blockMs,
        latencyMs,
        profile == 1,
        profile == 2,
        compressedAudio,
        adaptiveBuffer);
  }

  StreamSettings withCompression(boolean enabled) {
    return new StreamSettings(
        wholeDevice,
        bufferMs,
        blockMs,
        latencyMs,
        lowLatency,
        highQuality,
        enabled,
        adaptiveBuffer);
  }

  StreamSettings withBuffers(int buffer, int block, int latency) {
    return new StreamSettings(
        wholeDevice, buffer, block, latency, false, false, compressedAudio, adaptiveBuffer);
  }

  StreamSettings withAdaptiveBuffer(boolean enabled) {
    return new StreamSettings(
        wholeDevice,
        bufferMs,
        blockMs,
        latencyMs,
        lowLatency,
        highQuality,
        compressedAudio,
        enabled);
  }

  boolean effectiveAdaptiveBuffer() {
    return lowLatency && adaptiveBuffer;
  }

  int profile() {
    return lowLatency ? 1 : highQuality ? 2 : 0;
  }

  int samplesPerRead() {
    return 2 * (44100 * effectiveBlockMs() / 1000);
  }

  int effectiveBufferMs() {
    return lowLatency ? (adaptiveBuffer ? 8 : 0) : highQuality ? 150 : bufferMs;
  }

  int effectiveBlockMs() {
    return lowLatency ? 2 : highQuality ? 10 : blockMs;
  }

  int effectiveLatencyMs() {
    return lowLatency ? 10 : highQuality ? 1500 : latencyMs;
  }
}
