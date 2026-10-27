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
    ReceiverTiming receiverTiming,
    boolean shortAudioPackets,
    boolean shortMixerPeriod,
    boolean manualMode,
    dev.ryuya.homepodbridge.halprobe.PhoneTiming phoneTiming,
    int shortPacketFrames) {
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
      boolean dedicatedCapture,
      ReceiverTiming receiverTiming,
      boolean shortAudioPackets,
      boolean shortMixerPeriod,
      boolean manualMode,
      dev.ryuya.homepodbridge.halprobe.PhoneTiming phoneTiming) {
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
        receiverTiming,
        shortAudioPackets,
        shortMixerPeriod,
        manualMode,
        phoneTiming,
        88);
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
      int deviceBufferFrames,
      boolean dedicatedCapture,
      ReceiverTiming receiverTiming,
      boolean shortAudioPackets,
      boolean shortMixerPeriod) {
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
        receiverTiming,
        shortAudioPackets,
        shortMixerPeriod,
        false,
        dev.ryuya.homepodbridge.halprobe.PhoneTiming.fastest());
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
      int deviceBufferFrames,
      boolean dedicatedCapture,
      ReceiverTiming receiverTiming,
      boolean shortAudioPackets) {
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
        receiverTiming,
        shortAudioPackets,
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
      boolean deviceBuffer,
      int deviceBufferFrames,
      boolean dedicatedCapture,
      ReceiverTiming receiverTiming) {
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
        receiverTiming,
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
    if (shortPacketFrames != 44 && shortPacketFrames != 88)
      throw new IllegalArgumentException("Unsupported packet size");
    bufferMs =
        BufferLimits.clamp(bufferMs, BufferLimits.RESERVE_MIN_MS, BufferLimits.RESERVE_MAX_MS);
    blockMs = BufferLimits.clamp(blockMs, BufferLimits.CAPTURE_MIN_MS, BufferLimits.CAPTURE_MAX_MS);
    latencyMs =
        BufferLimits.clamp(latencyMs, BufferLimits.TIMING_MIN_MS, BufferLimits.TIMING_MAX_MS);
    deviceBufferFrames = deviceBufferFrames == 928 ? 928 : 1856;
    if (lowLatency) highQuality = false;
    if (lowLatency || highQuality) manualMode = false;
    if (phoneTiming == null) phoneTiming = dev.ryuya.homepodbridge.halprobe.PhoneTiming.fastest();
    if (receiverTiming == null) receiverTiming = ReceiverTiming.linked(latencyMs);
  }

  static StreamSettings load(Context context) {
    return read(context.getSharedPreferences("stream", Context.MODE_PRIVATE));
  }

  private static StreamSettings read(android.content.SharedPreferences preferences) {
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
    return saved
        .withReceiverTiming(
            new ReceiverTiming(
                preferences.getBoolean("independentReceiverTiming", false),
                preferences.getInt("receiverMinimumMs", Math.min(target, 250)),
                preferences.getInt("receiverMaximumMs", target),
                preferences.getInt("presentationOffsetMs", target)))
        .withShortAudioPackets(preferences.getBoolean("shortAudioPackets", false))
        .withShortPacketFrames(preferences.getInt("shortPacketFrames", 88) == 44 ? 44 : 88)
        .withShortMixerPeriod(preferences.getBoolean("shortMixerPeriod", false))
        .withManualMode(preferences.getBoolean("manualMode", false))
        .withPhoneTiming(
            dev.ryuya.homepodbridge.halprobe.PhoneTiming.fromStored(
                preferences.getInt("phonePeriodMs", 2),
                preferences.getInt("phoneReserveFrames", 96)));
  }

  static StreamSettings savedManual(Context context) {
    var current = load(context);
    var saved = context.getSharedPreferences("manualStream", Context.MODE_PRIVATE);
    return (saved.contains("manualMode") ? read(saved).withManualMode(true) : current.asManual())
        .withWholeDevice(current.wholeDevice());
  }

  void save(Context context) {
    saveTo(context.getSharedPreferences("stream", Context.MODE_PRIVATE));
    if (manualMode) saveTo(context.getSharedPreferences("manualStream", Context.MODE_PRIVATE));
  }

  private void saveTo(android.content.SharedPreferences preferences) {
    preferences
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
        .putBoolean("shortAudioPackets", shortAudioPackets)
        .putInt("shortPacketFrames", shortPacketFrames)
        .putBoolean("shortMixerPeriod", shortMixerPeriod)
        .putBoolean("manualMode", manualMode)
        .putInt("phonePeriodMs", phoneTiming.periodMs())
        .putInt("phoneReserveFrames", phoneTiming.reserveFrames())
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
        receiverTiming,
        shortAudioPackets,
        shortMixerPeriod,
        manualMode,
        phoneTiming,
        shortPacketFrames);
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
        receiverTiming,
        shortAudioPackets,
        shortMixerPeriod,
        profile == 3,
        phoneTiming,
        shortPacketFrames);
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
        receiverTiming,
        shortAudioPackets,
        shortMixerPeriod,
        manualMode,
        phoneTiming,
        shortPacketFrames);
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
        receiverTiming,
        shortAudioPackets,
        shortMixerPeriod,
        manualMode,
        phoneTiming,
        shortPacketFrames);
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
        receiverTiming,
        shortAudioPackets,
        shortMixerPeriod,
        manualMode,
        phoneTiming,
        shortPacketFrames);
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
        receiverTiming,
        shortAudioPackets,
        shortMixerPeriod,
        manualMode,
        phoneTiming,
        shortPacketFrames);
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
        receiverTiming,
        shortAudioPackets,
        shortMixerPeriod,
        manualMode,
        phoneTiming,
        shortPacketFrames);
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
        receiverTiming,
        shortAudioPackets,
        shortMixerPeriod,
        manualMode,
        phoneTiming,
        shortPacketFrames);
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
        timing,
        shortAudioPackets,
        shortMixerPeriod,
        manualMode,
        phoneTiming,
        shortPacketFrames);
  }

  StreamSettings withShortAudioPackets(boolean enabled) {
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
        receiverTiming,
        enabled,
        shortMixerPeriod,
        manualMode,
        phoneTiming,
        shortPacketFrames);
  }

  StreamSettings withShortMixerPeriod(boolean enabled) {
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
        receiverTiming,
        shortAudioPackets,
        enabled,
        manualMode,
        phoneTiming,
        shortPacketFrames);
  }

  StreamSettings withManualMode(boolean enabled) {
    return new StreamSettings(
        wholeDevice,
        bufferMs,
        blockMs,
        latencyMs,
        enabled ? false : lowLatency,
        enabled ? false : highQuality,
        compressedAudio,
        adaptiveBuffer,
        deviceBuffer,
        deviceBufferFrames,
        dedicatedCapture,
        receiverTiming,
        shortAudioPackets,
        shortMixerPeriod,
        enabled,
        phoneTiming,
        shortPacketFrames);
  }

  StreamSettings withPhoneTiming(dev.ryuya.homepodbridge.halprobe.PhoneTiming timing) {
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
        receiverTiming,
        shortAudioPackets,
        shortMixerPeriod,
        manualMode,
        timing,
        shortPacketFrames);
  }

  dev.ryuya.homepodbridge.halprobe.PhoneTiming effectivePhoneTiming() {
    return manualMode ? phoneTiming : dev.ryuya.homepodbridge.halprobe.PhoneTiming.fastest();
  }

  StreamSettings asManual() {
    return new StreamSettings(
        wholeDevice,
        effectiveBufferMs(),
        effectiveBlockMs(),
        requestedPresentationMs(),
        false,
        false,
        compressedAudio,
        false,
        deviceBuffer,
        deviceBufferFrames,
        effectiveDedicatedCapture(),
        new ReceiverTiming(
            true,
            requestedReceiverMinimumMs(),
            requestedReceiverMaximumMs(),
            requestedPresentationMs()),
        effectiveShortAudioPackets(),
        effectiveShortMixerPeriod(),
        true,
        effectiveShortMixerPeriod()
            ? effectivePhoneTiming()
            : new dev.ryuya.homepodbridge.halprobe.PhoneTiming(21, 800),
        shortPacketFrames);
  }

  StreamSettings preset(int selected) {
    if (selected == 3) return asManual();
    if (selected < 0 || selected > 2) throw new IllegalArgumentException();
    var base = withProfile(selected);
    if (selected == 0) base = base.withBuffers(150, 10, 1500);
    return base.withAdaptiveBuffer(false)
        .withCompression(false)
        .withDedicatedCapture(selected == 1)
        .withShortMixerPeriod(selected == 1)
        .withShortAudioPackets(false)
        .withReceiverTiming(ReceiverTiming.linked(selected == 1 ? 50 : 1500));
  }

  boolean effectiveShortMixerPeriod() {
    return effectiveDedicatedCapture() && (manualMode || shortMixerPeriod);
  }

  boolean effectiveShortAudioPackets() {
    return effectiveDedicatedCapture() && shortAudioPackets;
  }

  StreamSettings withShortPacketFrames(int frames) {
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
        receiverTiming,
        shortAudioPackets,
        shortMixerPeriod,
        manualMode,
        phoneTiming,
        frames);
  }

  int requestedPacketFrames() {
    return packetFramesForBackend(effectiveDedicatedCapture());
  }

  int packetFramesForBackend(boolean dedicatedActive) {
    return dedicatedActive && effectiveShortAudioPackets() ? shortPacketFrames : 352;
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
    return (lowLatency || manualMode) && dedicatedCapture;
  }

  boolean effectiveDeviceBuffer() {
    return (lowLatency || manualMode) && deviceBuffer;
  }

  boolean effectiveAdaptiveBuffer() {
    return !manualMode && lowLatency && adaptiveBuffer;
  }

  int profile() {
    return manualMode ? 3 : lowLatency ? 1 : highQuality ? 2 : 0;
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
