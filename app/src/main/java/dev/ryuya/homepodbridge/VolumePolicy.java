package dev.ryuya.homepodbridge;

final class VolumePolicy {
  static int clamp(int requested, int maximum) {
    return Math.max(0, Math.min(Math.min(100, maximum), requested));
  }

  static int apply(int requested) {
    return clamp(requested, BuildConfig.VOLUME_LIMIT);
  }

  private VolumePolicy() {}
}
