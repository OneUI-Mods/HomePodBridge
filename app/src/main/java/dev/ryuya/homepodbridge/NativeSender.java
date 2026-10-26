package dev.ryuya.homepodbridge;

final class NativeSender {
  static {
    System.loadLibrary("homepod_bridge");
  }

  private NativeSender() {}

  static native void start(
      String host,
      int port,
      String name,
      String deviceId,
      String identity,
      int volumeLimit,
      int bufferMs,
      int latencyMs,
      boolean independentTiming,
      int receiverMinimumMs,
      int receiverMaximumMs,
      boolean compressedAudio,
      boolean adaptiveBuffer);

  static native boolean write(java.nio.ByteBuffer pcm, int byteCount);

  static native int state();

  static native int[] timing();

  static native long[] pipeline();

  static native void volume(int volume);

  static native void stop();

  static native String diagnostic();
}
