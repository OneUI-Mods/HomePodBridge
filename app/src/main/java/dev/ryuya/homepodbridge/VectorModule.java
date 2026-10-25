package dev.ryuya.homepodbridge;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaRoute2Info;
import android.media.RoutingSessionInfo;
import android.net.Uri;
import android.os.Bundle;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Publishes our routes inside SystemUI. Original routes and system_server are untouched. */
public final class VectorModule implements IXposedHookLoadPackage, IXposedHookZygoteInit {
  private static boolean installed;
  private String modulePath;

  @Override
  public void initZygote(StartupParam startup) {
    modulePath = startup.modulePath;
  }

  @Override
  public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
    if (!"com.android.systemui".equals(load.packageName) || !load.isFirstApplication) return;
    synchronized (VectorModule.class) {
      if (installed) return;
      installed = true;
    }
    XposedHelpers.findAndHookMethod(
        Application.class,
        "attach",
        Context.class,
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam param) {
            Context context = (Context) param.args[0];
            SystemRouteTransfer.context = context;
            try {
              SystemAudioBridge bridge = new SystemAudioBridge(context, modulePath);
              Runnable register =
                  () ->
                      new Thread(
                              () -> {
                                try {
                                  Bundle registration = new Bundle();
                                  registration.putBinder("bridge", bridge);
                                  context
                                      .getContentResolver()
                                      .call(
                                          Uri.parse("content://" + RootProtocol.AUTHORITY),
                                          "register",
                                          null,
                                          registration);
                                  XposedBridge.log(
                                      "HomePod Bridge: system audio bridge registered");
                                } catch (Throwable e) {
                                  XposedBridge.log(
                                      "HomePod Bridge: system audio bridge unavailable ("
                                          + e.getClass().getSimpleName()
                                          + ")");
                                }
                              },
                              "HomePod-register")
                          .start();
              context.registerReceiver(
                  new BroadcastReceiver() {
                    private long last;

                    @Override
                    public void onReceive(Context receiverContext, Intent intent) {
                      long now = android.os.SystemClock.elapsedRealtime();
                      if (now - last < 1000) return;
                      last = now;
                      register
                          .run(); // A request can only register our Binder with our own provider.
                    }
                  },
                  new IntentFilter(RootProtocol.REQUEST),
                  Context.RECEIVER_EXPORTED);
              register.run();
            } catch (Throwable e) {
              XposedBridge.log(
                  "HomePod Bridge: bridge initialization failed ("
                      + e.getClass().getSimpleName()
                      + ")");
            }
          }
        });
    Class<?> manager =
        XposedHelpers.findClassIfExists("android.media.MediaRouter2Manager", load.classLoader);
    if (manager == null) return;
    SystemRouteTransfer.install(manager);
    Class<?> proxy =
        XposedHelpers.findClassIfExists(
            "android.media.MediaRouter2$ProxyMediaRouter2Impl", load.classLoader);
    if (proxy != null) SystemRouteTransfer.install(proxy);
    XC_MethodHook includeBridge =
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam param) {
            if (param.hasThrowable()
                || param.args.length != 1
                || !(param.getResult() instanceof List<?> original)) return;
            Object target = param.args[0];
            String client =
                target instanceof String
                    ? (String) target
                    : target instanceof RoutingSessionInfo
                        ? ((RoutingSessionInfo) target).getClientPackageName()
                        : null;
            if (client == null || client.isEmpty() || RootProtocol.PACKAGE.equals(client)) return;
            try {
              Object all = XposedHelpers.callMethod(param.thisObject, "getAllRoutes");
              if (!(all instanceof List<?> allRoutes)) return;
              LinkedHashMap<String, MediaRoute2Info> merged = new LinkedHashMap<>();
              for (Object route : original)
                if (route instanceof MediaRoute2Info info) merged.put(info.getId(), info);
              for (Object route : allRoutes) {
                if (!(route instanceof MediaRoute2Info info)) continue;
                if ("dev.ryuya.homepodbridge".equals(providerPackage(info))
                    && info.getFeatures().contains("dev.ryuya.homepodbridge.AIRPLAY"))
                  merged.putIfAbsent(info.getId(), info);
              }
              param.setResult(new ArrayList<>(merged.values()));
            } catch (Throwable ignored) {
              // ROM signatures may change. Preserve the original result on failure.
            }
          }
        };
    XposedBridge.hookAllMethods(manager, "getAvailableRoutes", includeBridge);
    XposedBridge.hookAllMethods(manager, "getTransferableRoutes", includeBridge);
  }

  private static String providerPackage(MediaRoute2Info route) {
    try {
      return (String) XposedHelpers.callMethod(route, "getProviderPackageName");
    } catch (NoSuchMethodError e) {
      return (String) XposedHelpers.callMethod(route, "getPackageName");
    }
  }
}
