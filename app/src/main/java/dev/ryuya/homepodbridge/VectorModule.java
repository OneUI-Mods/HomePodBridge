package dev.ryuya.homepodbridge;

import android.media.MediaRoute2Info;
import android.media.RoutingSessionInfo;
import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Adds only our routes, only for Apple Music. Original routes and system_server are untouched. */
public final class VectorModule implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private String modulePath;
    @Override public void initZygote(StartupParam startup) { modulePath = startup.modulePath; }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!"com.android.systemui".equals(load.packageName) || !load.isFirstApplication) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                new Thread(() -> {
                    try {
                        Bundle registration = new Bundle();
                        registration.putBinder("bridge", new SystemAudioBridge(context, modulePath));
                        context.getContentResolver().call(Uri.parse("content://" + RootProtocol.AUTHORITY), "register", null, registration);
                        XposedBridge.log("HomePod Bridge: system audio bridge registered");
                    } catch (Throwable e) { XposedBridge.log("HomePod Bridge: system audio bridge unavailable (" + e.getClass().getSimpleName() + ")"); }
                }, "HomePod-register").start();
            }
        });
        Class<?> manager = XposedHelpers.findClassIfExists("android.media.MediaRouter2Manager", load.classLoader);
        if (manager == null) return;
        XC_MethodHook includeBridge = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.args.length != 1 || !(param.getResult() instanceof List<?> original)) return;
                Object target = param.args[0];
                String client = target instanceof String ? (String) target
                        : target instanceof RoutingSessionInfo ? ((RoutingSessionInfo) target).getClientPackageName() : null;
                if (client == null || client.isEmpty() || RootProtocol.PACKAGE.equals(client)) return;
                try {
                    Object all = XposedHelpers.callMethod(param.thisObject, "getAllRoutes");
                    if (!(all instanceof List<?> allRoutes)) return;
                    LinkedHashMap<String, MediaRoute2Info> merged = new LinkedHashMap<>();
                    for (Object route : original) if (route instanceof MediaRoute2Info info) merged.put(info.getId(), info);
                    for (Object route : allRoutes) {
                        if (!(route instanceof MediaRoute2Info info)) continue;
                        if ("dev.ryuya.homepodbridge".equals(providerPackage(info))
                                && info.getFeatures().contains("dev.ryuya.homepodbridge.AIRPLAY")) merged.putIfAbsent(info.getId(), info);
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
        try { return (String) XposedHelpers.callMethod(route, "getProviderPackageName"); }
        catch (NoSuchMethodError e) { return (String) XposedHelpers.callMethod(route, "getPackageName"); }
    }
}
