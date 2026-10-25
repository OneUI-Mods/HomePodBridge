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
      Class<?> callbacks =
          XposedHelpers.findClass(
              "com.android.systemui.volume.VolumeDialogControllerImpl$MediaSessionsCallbacks",
              loader);
      XposedBridge.hookAllMethods(
          callbacks,
          "onRemoteUpdate",
          new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
              SystemVolumeEndpoint endpoint = active;
              if (endpoint != null && param.args.length == 3) {
                String name = endpoint.receiverNameFor(param.args[0]);
                if (name != null) param.args[1] = name;
              }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
              SystemVolumeEndpoint endpoint = active;
              if (endpoint != null && param.args.length == 3 && !param.hasThrowable())
                endpoint.rememberRemoteStream(param.thisObject, param.args[0]);
            }
          });
      available = true;
      // One UI's default reducer leaves a dynamic row's level unchanged on an icon click.
      // Sound Assistant shares this reducer. Keep its ordinary controller/MediaSession path.
      Class<?> reducer =
          XposedHelpers.findClassIfExists(
              "com.android.systemui.volume.reducer.VolumePanelReducer", loader);
      if (reducer != null)
        XposedBridge.hookAllMethods(
            reducer,
            "getLastAudibleLevelOrMinLevel",
            new XC_MethodHook() {
              @Override
              protected void beforeHookedMethod(MethodHookParam param) {
                SystemVolumeEndpoint endpoint = active;
                if (endpoint == null || param.args.length != 2) return;
                Integer target = endpoint.iconTarget(param.args[1]);
                if (target != null) param.setResult(target);
              }
            });
    } catch (Throwable error) {
      XposedBridge.log(
          "HomePod Bridge: remote volume panel unavailable ("
              + error.getClass().getSimpleName()
              + ")");
    }
  }
}
