package dev.ryuya.homepodbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import java.util.HashSet;
import java.util.Set;

/** Filters only volume keys during active whole-device playback. No window-content capability. */
public final class VolumeKeyService extends AccessibilityService {
  private final Set<Integer> held = new HashSet<>();

  static boolean enabled(Context context) {
    AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
    return manager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .stream()
        .anyMatch(
            info -> {
              var service = info.getResolveInfo().serviceInfo;
              return service.packageName.equals(context.getPackageName())
                  && service.name.equals(VolumeKeyService.class.getName());
            });
  }

  @Override
  protected boolean onKeyEvent(KeyEvent event) {
    int key = event.getKeyCode();
    int direction =
        switch (key) {
          case KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE;
          case KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER;
          case KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE;
          default -> Integer.MIN_VALUE;
        };
    if (direction == Integer.MIN_VALUE) return false;
    if (event.getAction() == KeyEvent.ACTION_UP) return held.remove(key);
    if (event.getAction() != KeyEvent.ACTION_DOWN
        || getSystemService(AudioManager.class).getMode() != AudioManager.MODE_NORMAL
        || BridgeState.capture == null) return false;
    if (key == KeyEvent.KEYCODE_VOLUME_MUTE && event.getRepeatCount() > 0)
      return held.contains(key);
    if (!BridgeState.capture.adjustWholeDeviceVolume(direction)) return false;
    held.add(key);
    return true;
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {}

  @Override
  public void onInterrupt() {
    held.clear();
  }
}
