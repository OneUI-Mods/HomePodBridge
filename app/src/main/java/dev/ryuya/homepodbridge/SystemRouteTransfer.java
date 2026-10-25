package dev.ryuya.homepodbridge;

import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.RoutingSessionInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** SystemUI owns these sessions; the source app's Cast router cannot accept an AirPlay route. */
final class SystemRouteTransfer {
  static volatile Context context;

  private record Pending(Object manager, RoutingSessionInfo old, MediaRoute2Info route) {}

  private static final ConcurrentHashMap<String, Pending> selections = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, RoutingSessionInfo> stops =
      new ConcurrentHashMap<>();

  static void install(Class<?> manager) {
    XposedBridge.hookAllMethods(
        manager,
        "transfer",
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam param) {
            if (context == null
                || param.args.length < 2
                || !(param.args[0] instanceof RoutingSessionInfo old)
                || !(param.args[1] instanceof MediaRoute2Info route)) return;
            try {
              if (isBridgeRoute(route)) {
                String source = old.getClientPackageName();
                if (source == null || source.isEmpty()) return;
                Pending pending = new Pending(param.thisObject, old, route);
                selections.put(source, pending);
                Bundle args = new Bundle();
                args.putString("source", source);
                String id = route.getId();
                args.putString("route", id.substring(id.lastIndexOf(':') + 1));
                control("select", args, () -> fail(source, pending));
                new Handler(Looper.getMainLooper())
                    .postDelayed(() -> fail(source, pending), 26_000);
                param.setResult(null);
              } else if (isBridgeSession(old)) {
                RoutingSessionInfo local =
                    localSession(param.thisObject, old.getClientPackageName());
                if (local == null) return;
                if ((boolean) XposedHelpers.callMethod(route, "isSystemRoute")) {
                  stops.put(old.getId(), local);
                  control("stop", null, () -> stops.remove(old.getId()));
                  param.setResult(null);
                } else {
                  param.args[0] = local;
                  control("stop", null, () -> {});
                }
              }
            } catch (Throwable error) {
              XposedBridge.log(
                  "HomePod Bridge: transfer hook unavailable ("
                      + error.getClass().getSimpleName()
                      + ")");
            }
          }
        });
    XposedBridge.hookAllMethods(
        manager,
        "createSessionOnHandler",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam param) {
            if (param.hasThrowable()
                || param.args.length != 2
                || !(param.args[1] instanceof RoutingSessionInfo session)
                || !isBridgeSession(session)) return;
            try {
              Pending pending = selections.remove(session.getClientPackageName());
              if (pending != null)
                XposedHelpers.callMethod(
                    param.thisObject, "notifyTransferred", pending.old(), session);
            } catch (Throwable ignored) {
            }
          }
        });
    XposedBridge.hookAllMethods(
        manager,
        "notifySessionReleased",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam param) {
            if (param.hasThrowable()
                || param.args.length != 1
                || !(param.args[0] instanceof RoutingSessionInfo old)
                || !isBridgeSession(old)) return;
            try {
              RoutingSessionInfo local = stops.remove(old.getId());
              if (local != null)
                XposedHelpers.callMethod(param.thisObject, "notifyTransferred", old, local);
            } catch (Throwable ignored) {
            }
          }
        });
  }

  private static void fail(String source, Pending pending) {
    if (!selections.remove(source, pending)) return;
    try {
      XposedHelpers.callMethod(
          pending.manager(), "notifyTransferFailed", pending.old(), pending.route());
    } catch (Throwable ignored) {
    }
  }

  private static RoutingSessionInfo localSession(Object manager, String source) {
    Object sessions = XposedHelpers.callMethod(manager, "getRoutingSessions", source);
    if (sessions instanceof List<?> list)
      for (Object item : list)
        if (item instanceof RoutingSessionInfo session
            && (boolean) XposedHelpers.callMethod(session, "isSystemSession")) return session;
    return null;
  }

  private static boolean isBridgeRoute(MediaRoute2Info route) {
    return route.getId().startsWith("dev.ryuya.homepodbridge/")
        && route.getFeatures().contains("dev.ryuya.homepodbridge.AIRPLAY");
  }

  private static boolean isBridgeSession(RoutingSessionInfo session) {
    return session.getId().startsWith("dev.ryuya.homepodbridge/");
  }

  private static void control(String method, Bundle args, Runnable failure) {
    new Thread(
            () -> {
              try {
                Bundle result =
                    context
                        .getContentResolver()
                        .call(Uri.parse("content://" + RootProtocol.AUTHORITY), method, null, args);
                if (result == null || !result.getBoolean("accepted")) failure.run();
              } catch (Throwable error) {
                failure.run();
              }
            },
            "HomePod-route")
        .start();
  }

  private SystemRouteTransfer() {}
}
