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
    boolean dedicatedCapture,
    ReceiverTiming receiverTiming) {
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
      int deviceBufferFrames,
      boolean dedicatedCapture) {
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
        dedicatedCapture,
        ReceiverTiming.linked(lowLatency ? 50 : highQuality ? 1500 : latencyMs));
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
    bufferMs =
        BufferLimits.clamp(bufferMs, BufferLimits.RESERVE_MIN_MS, BufferLimits.RESERVE_MAX_MS);
    blockMs = BufferLimits.clamp(blockMs, BufferLimits.CAPTURE_MIN_MS, BufferLimits.CAPTURE_MAX_MS);
    latencyMs =
        BufferLimits.clamp(latencyMs, BufferLimits.TIMING_MIN_MS, BufferLimits.TIMING_MAX_MS);
    deviceBufferFrames = deviceBufferFrames == 928 ? 928 : 1856;
    if (lowLatency) highQuality = false;
    if (receiverTiming == null) receiverTiming = ReceiverTiming.linked(latencyMs);
  }

  static StreamSettings load(Context context) {
    var preferences = context.getSharedPreferences("stream", Context.MODE_PRIVATE);
    var saved =
        new StreamSettings(
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
    int target = saved.effectiveLatencyMs();
    return saved.withReceiverTiming(
        new ReceiverTiming(
            preferences.getBoolean("independentReceiverTiming", false),
            preferences.getInt("receiverMinimumMs", Math.min(target, 250)),
            preferences.getInt("receiverMaximumMs", target),
            preferences.getInt("presentationOffsetMs", target)));
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
        .putBoolean("independentReceiverTiming", receiverTiming.independent())
        .putInt("receiverMinimumMs", receiverTiming.minimumMs())
        .putInt("receiverMaximumMs", receiverTiming.maximumMs())
        .putInt("presentationOffsetMs", receiverTiming.presentationMs())
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
        dedicatedCapture,
        receiverTiming);
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
        dedicatedCapture,
        receiverTiming);
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
        dedicatedCapture,
        receiverTiming);
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
        dedicatedCapture,
        receiverTiming);
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
        dedicatedCapture,
        receiverTiming);
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
        dedicatedCapture,
        receiverTiming);
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
        dedicatedCapture,
        receiverTiming);
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
        enabled,
        receiverTiming);
  }

  StreamSettings withReceiverTiming(ReceiverTiming timing) {
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
        dedicatedCapture,
        timing);
  }

  int requestedReceiverMinimumMs() {
    return receiverTiming.independent()
        ? receiverTiming.minimumMs()
        : Math.min(effectiveLatencyMs(), 250);
  }

  int requestedReceiverMaximumMs() {
    return receiverTiming.independent() ? receiverTiming.maximumMs() : effectiveLatencyMs();
  }

  int requestedPresentationMs() {
    return receiverTiming.independent() ? receiverTiming.presentationMs() : effectiveLatencyMs();
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

  double captureUnitMs() {
    return effectiveDedicatedCapture()
        ? BufferLimits.DEDICATED_FRAMES * 1000.0 / 44100
        : effectiveBlockMs();
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
