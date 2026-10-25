package dev.ryuya.homepodbridge;

/** A single stale panel write immediately following an explicit remote icon command. */
final class VolumeEchoGuard {
  private int previous = -1;
  private long expires;

  void explicitChange(int before, int after, long now) {
    previous = before == after ? -1 : VolumePolicy.apply(before);
    expires = now + 150;
  }

  boolean ignore(int value, long now) {
    if (previous < 0) return false;
    boolean stale = now <= expires && value == previous;
    previous = -1; // A different/new write or expiry also consumes the guard.
    return stale;
  }

  void clear() {
    previous = -1;
  }
}
