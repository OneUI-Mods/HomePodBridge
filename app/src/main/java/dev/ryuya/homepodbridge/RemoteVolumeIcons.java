package dev.ryuya.homepodbridge;

/** Only the current lease's dynamic row may toggle its own remembered audible volume. */
final class RemoteVolumeIcons {
  volatile int stream = -1;
  private int audible = 1;

  boolean owns(int requestedStream) {
    return stream >= 100 && stream == requestedStream;
  }

  synchronized Integer target(int requestedStream, int level) {
    if (!owns(requestedStream)) return null;
    if (level > 0) {
      audible = VolumePolicy.apply(level);
      return 0;
    }
    return audible;
  }
}
