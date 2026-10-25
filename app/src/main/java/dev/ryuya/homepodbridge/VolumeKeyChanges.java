package dev.ryuya.homepodbridge;

import java.util.ArrayList;
import java.util.List;

/** Pairs local-volume notifications with explicit key UI events, never with app volume writes. */
final class VolumeKeyChanges {
  private record Change(int percent, long atMs) {}

  private final List<Change> changes = new ArrayList<>();
  private final List<Long> keys = new ArrayList<>();
  private final int baseline;

  VolumeKeyChanges(int baseline) {
    this.baseline = VolumePolicy.apply(baseline);
  }

  void changed(int percent, long now) {
    if (changes.size() == 32) changes.remove(0);
    changes.add(new Change(VolumePolicy.apply(percent), now));
  }

  void key(long now) {
    if (keys.size() == 16) keys.remove(0);
    keys.add(now);
  }

  boolean pendingKeys() {
    return !keys.isEmpty();
  }

  List<Integer> drain(long now) {
    List<Integer> directions = new ArrayList<>();
    while (!keys.isEmpty() && now - keys.get(0) >= 24) {
      long key = keys.get(0);
      Change best = null;
      long bestDistance = Long.MAX_VALUE;
      for (Change change : changes) {
        long distance = Math.abs(change.atMs - key);
        if (distance > 100) continue;
        // Prefer the actual adjustment over the restoration of our unchanged baseline.
        long score = distance + (change.percent == baseline ? 100 : 0);
        if (score < bestDistance) {
          best = change;
          bestDistance = score;
        }
      }
      if (best == null && now - key < 160) break;
      keys.remove(0);
      if (best == null) continue;
      changes.remove(best);
      int direction = Integer.compare(best.percent, baseline);
      if (direction == 0) direction = baseline == 0 ? -1 : baseline == 100 ? 1 : 0;
      if (direction != 0) directions.add(direction);
    }
    changes.removeIf(change -> now - change.atMs > 200);
    return directions;
  }
}
