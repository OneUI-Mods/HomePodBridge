package dev.ryuya.homepodbridge

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRoute2Info
import android.media.RoutingSessionInfo
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.IXposedHookZygoteInit.StartupParam
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.ArrayList
import java.util.LinkedHashMap
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Publishes our routes inside SystemUI. Original routes and system_server are untouched. */
class VectorModule : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private var modulePath: String? = null

    override fun initZygote(startup: StartupParam) {
        modulePath = startup.modulePath
    }

    override fun handleLoadPackage(load: LoadPackageParam) {
        if ("com.android.systemui" != load.packageName || !load.isFirstApplication) return
        synchronized(VectorModule::class.java) {
            if (VectorModule.installed) return
            VectorModule.installed = true
        }
        SystemVolumeUi.install(load.classLoader)
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context: Context? = param.args[0] as Context?
                    SystemRouteTransfer.context = context
                    try {
                        val bridge = SystemAudioBridge(context!!, modulePath)
                        val register = Runnable {
                            Thread(
                                    Runnable {
                                        try {
                                            val registration = Bundle()
                                            registration.putBinder("bridge", bridge)
                                            context!!
                                                .getContentResolver()
                                                .call(
                                                    Uri.parse(
                                                        "content://" + RootProtocol.AUTHORITY
                                                    ),
                                                    "register",
                                                    null,
                                                    registration,
                                                )
                                            XposedBridge.log(
                                                "HomePod Bridge: system audio bridge registered"
                                            )
                                        } catch (e: Throwable) {
                                            XposedBridge.log(
                                                ("HomePod Bridge: system audio bridge unavailable (" +
                                                    e.javaClass.getSimpleName() +
                                                    ")")
                                            )
                                        }
                                    },
                                    "HomePod-register",
                                )
                                .start()
                        }
                        context!!.registerReceiver(
                            object : BroadcastReceiver() {
                                private var last: Long = 0

                                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - last < 1000) return
                                    last = now
                                    register
                                        .run() // A request can only register our Binder with our
                                    // own provider.
                                }
                            },
                            IntentFilter(RootProtocol.REQUEST),
                            Context.RECEIVER_EXPORTED,
                        )
                        register.run()
                    } catch (e: Throwable) {
                        XposedBridge.log(
                            ("HomePod Bridge: bridge initialization failed (" +
                                e.javaClass.getSimpleName() +
                                ")")
                        )
                    }
                }
            },
        )
        val manager =
            XposedHelpers.findClassIfExists("android.media.MediaRouter2Manager", load.classLoader)
        if (manager == null) return
        SystemRouteTransfer.install(manager)
        val proxy =
            XposedHelpers.findClassIfExists(
                "android.media.MediaRouter2\$ProxyMediaRouter2Impl",
                load.classLoader,
            )
        if (proxy != null) SystemRouteTransfer.install(proxy)
        val includeBridge: XC_MethodHook =
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val patternPrefix0 = param.hasThrowable() || param.args.size != 1
                    val original =
                        if (!patternPrefix0) param.getResult() as? MutableList<*> else null
                    if (patternPrefix0 || original == null) return
                    val target: Any? = param.args[0]
                    val client: String? =
                        if (target is String) target as String?
                        else if (target is RoutingSessionInfo)
                            (target as RoutingSessionInfo).getClientPackageName()
                        else null
                    if (client == null || client.isEmpty() || RootProtocol.PACKAGE == client) return
                    try {
                        val all = XposedHelpers.callMethod(param.thisObject, "getAllRoutes")
                        if (all !is MutableList<*>) return
                        val merged = LinkedHashMap<String?, MediaRoute2Info?>()
                        for (route in original) if (route is MediaRoute2Info)
                            merged.put(route.getId(), route)
                        for (route in all) {
                            if (route !is MediaRoute2Info) continue
                            if (
                                "dev.ryuya.homepodbridge" == VectorModule.providerPackage(route) &&
                                    route.getFeatures().contains("dev.ryuya.homepodbridge.AIRPLAY")
                            )
                                merged.putIfAbsent(route.getId(), route)
                        }
                        param.setResult(ArrayList<MediaRoute2Info?>(merged.values))
                    } catch (ignored: Throwable) {
                        // ROM signatures may change. Preserve the original result on failure.
                    }
                }
            }
        XposedBridge.hookAllMethods(manager, "getAvailableRoutes", includeBridge)
        XposedBridge.hookAllMethods(manager, "getTransferableRoutes", includeBridge)
    }

    companion object {
        private var installed: Boolean = false

        @JvmStatic
        private fun providerPackage(route: MediaRoute2Info?): String? {
            try {
                return XposedHelpers.callMethod(route, "getProviderPackageName") as String?
            } catch (e: NoSuchMethodError) {
                return XposedHelpers.callMethod(route, "getPackageName") as String?
            }
        }
    }
}
