package dev.ryuya.homepodbridge;

import android.media.AudioManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Redirects only the active whole-device lease's local-media panel to its remote session. */
final class SystemVolumeUi {
  static volatile SystemVolumeEndpoint active;
  static volatile boolean available;

  static void install(ClassLoader loader) {
    try {
      Class<?> controller =
          XposedHelpers.findClass("com.android.systemui.volume.VolumeDialogControllerImpl", loader);
      XposedHelpers.findAndHookMethod(
          controller,
          "onVolumeChangedW",
          int.class,
          int.class,
          new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
              SystemVolumeEndpoint endpoint = active;
              if (endpoint == null || (Integer) param.args[0] != AudioManager.STREAM_MUSIC) return;
              if (endpoint.redirectLocalUi(param.thisObject, (Integer) param.args[1]))
                param.setResult(false);
            }
          });
      available = true;
    } catch (Throwable error) {
      XposedBridge.log(
          "HomePod Bridge: remote volume panel unavailable ("
              + error.getClass().getSimpleName()
              + ")");
    }
  }
}
