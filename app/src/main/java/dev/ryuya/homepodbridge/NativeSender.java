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
      boolean compressedAudio);

  static native boolean write(short[] pcm, int count);

  static native int state();

  static native void volume(int volume);

  static native void stop();

  static native String diagnostic();
}
