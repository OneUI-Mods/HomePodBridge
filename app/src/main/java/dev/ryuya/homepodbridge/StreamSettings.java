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
    boolean adaptiveBuffer,
    boolean deviceBuffer,
    int deviceBufferFrames,
    boolean dedicatedCapture) {
  StreamSettings(
      boolean wholeDevice,
      int bufferMs,
      int blockMs,
      int latencyMs,
      boolean lowLatency,
      boolean highQuality,
      boolean compressedAudio,
      boolean adaptiveBuffer,
      boolean deviceBuffer,
      int deviceBufferFrames) {
    this(
        wholeDevice,
        bufferMs,
        blockMs,
        latencyMs,
        lowLatency,
        highQuality,
        compressedAudio,
        adaptiveBuffer,
        deviceBuffer,
        deviceBufferFrames,
        false);
  }

  StreamSettings(
      boolean wholeDevice,
      int bufferMs,
      int blockMs,
      int latencyMs,
      boolean lowLatency,
      boolean highQuality,
      boolean compressedAudio,
      boolean adaptiveBuffer,
      boolean deviceBuffer) {
    this(
        wholeDevice,
        bufferMs,
        blockMs,
        latencyMs,
        lowLatency,
        highQuality,
        compressedAudio,
        adaptiveBuffer,
        deviceBuffer,
        1856);
  }

  StreamSettings(
      boolean wholeDevice,
      int bufferMs,
      int blockMs,
      int latencyMs,
      boolean lowLatency,
      boolean highQuality,
      boolean compressedAudio,
      boolean adaptiveBuffer) {
    this(
        wholeDevice,
        bufferMs,
        blockMs,
        latencyMs,
        lowLatency,
        highQuality,
        compressedAudio,
        adaptiveBuffer,
        false);
  }

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
    deviceBufferFrames = deviceBufferFrames == 928 ? 928 : 1856;
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
        preferences.getBoolean("adaptiveBuffer", true),
        preferences.getBoolean("deviceBuffer", false),
        preferences.getInt("deviceBufferFrames", 1856),
        preferences.getBoolean("dedicatedCapture", false));
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
        .putBoolean("deviceBuffer", deviceBuffer)
        .putBoolean("dedicatedCapture", dedicatedCapture)
        .putInt("deviceBufferFrames", deviceBufferFrames)
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
        adaptiveBuffer,
        deviceBuffer,
        deviceBufferFrames,
        dedicatedCapture);
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
        adaptiveBuffer,
        deviceBuffer,
        deviceBufferFrames,
        dedicatedCapture);
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
        adaptiveBuffer,
        deviceBuffer,
        deviceBufferFrames,
        dedicatedCapture);
  }

  StreamSettings withBuffers(int buffer, int block, int latency) {
    return new StreamSettings(
        wholeDevice,
        buffer,
        block,
        latency,
        false,
        false,
        compressedAudio,
        adaptiveBuffer,
        deviceBuffer,
        deviceBufferFrames,
        dedicatedCapture);
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
        enabled,
        deviceBuffer,
        deviceBufferFrames,
        dedicatedCapture);
  }

  StreamSettings withDeviceBuffer(boolean enabled) {
    return new StreamSettings(
        wholeDevice,
        bufferMs,
        blockMs,
        latencyMs,
        lowLatency,
        highQuality,
        compressedAudio,
        adaptiveBuffer,
        enabled,
        deviceBufferFrames,
        dedicatedCapture);
  }

  StreamSettings withDeviceBufferFrames(int frames) {
    return new StreamSettings(
        wholeDevice,
        bufferMs,
        blockMs,
        latencyMs,
        lowLatency,
        highQuality,
        compressedAudio,
        adaptiveBuffer,
        deviceBuffer,
        frames,
        dedicatedCapture);
  }

  StreamSettings withDedicatedCapture(boolean enabled) {
    return new StreamSettings(
        wholeDevice,
        bufferMs,
        blockMs,
        latencyMs,
        lowLatency,
        highQuality,
        compressedAudio,
        adaptiveBuffer,
        deviceBuffer,
        deviceBufferFrames,
        enabled);
  }

  boolean effectiveDedicatedCapture() {
    return lowLatency && dedicatedCapture;
  }

  boolean effectiveDeviceBuffer() {
    return lowLatency && deviceBuffer;
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
    return lowLatency ? 50 : highQuality ? 1500 : latencyMs;
  }
}
