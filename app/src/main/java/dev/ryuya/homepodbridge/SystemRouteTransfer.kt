package dev.ryuya.homepodbridge

import android.content.Context
import android.media.MediaRoute2Info
import android.media.RoutingSessionInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* SystemUI owns these sessions; the source app's Cast router cannot accept an AirPlay route. */
object SystemRouteTransfer {
    @JvmField @Volatile var context: Context? = null

    private val selections: ConcurrentHashMap<String?, SystemRouteTransfer.Pending?> =
        ConcurrentHashMap<String?, SystemRouteTransfer.Pending?>()
    private val stops: ConcurrentHashMap<String?, SystemRouteTransfer.Stop?> =
        ConcurrentHashMap<String?, SystemRouteTransfer.Stop?>()

    @JvmStatic
    fun install(manager: Class<*>) {
        val proxy = manager.getName().contains("\$ProxyMediaRouter2Impl")
        val transfer: XC_MethodHook =
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val patternPrefix0 = SystemRouteTransfer.context == null || param.args.size < 2
                    val old = if (!patternPrefix0) param.args[0] as? RoutingSessionInfo else null
                    val patternPrefix1 = patternPrefix0 || old == null
                    val route = if (!patternPrefix1) param.args[1] as? MediaRoute2Info else null
                    if (patternPrefix1 || route == null) return
                    try {
                        if (SystemRouteTransfer.isBridgeRoute(route)) {
                            val source = old.getClientPackageName()
                            if (source == null || source.isEmpty()) return
                            val pending = SystemRouteTransfer.Pending(param.thisObject, old, route)
                            SystemRouteTransfer.selections.put(source, pending)
                            val args = Bundle()
                            args.putString("source", source)
                            val id = route.getId()
                            args.putString("route", id.substring(id.lastIndexOf(':') + 1))
                            SystemRouteTransfer.control(
                                "select",
                                args,
                                Runnable { SystemRouteTransfer.fail(source, pending) },
                            )
                            Handler(Looper.getMainLooper())
                                .postDelayed(
                                    Runnable { SystemRouteTransfer.fail(source, pending) },
                                    26000,
                                )
                            param.setResult(null)
                        } else if (SystemRouteTransfer.isBridgeSession(old)) {
                            val local =
                                SystemRouteTransfer.localSession(
                                    param.thisObject,
                                    old.getClientPackageName(),
                                    proxy,
                                )
                            if (local == null) return
                            if (XposedHelpers.callMethod(route, "isSystemRoute") as Boolean) {
                                SystemRouteTransfer.stops.put(
                                    old.getId(),
                                    SystemRouteTransfer.Stop(param.thisObject, local),
                                )
                                SystemRouteTransfer.control(
                                    "stop",
                                    null,
                                    Runnable { SystemRouteTransfer.stops.remove(old.getId()) },
                                )
                                param.setResult(null)
                            } else {
                                param.args[0] = local
                                SystemRouteTransfer.control("stop", null, Runnable {})
                            }
                        }
                    } catch (error: Throwable) {
                        XposedBridge.log(
                            ("HomePod Bridge: transfer hook unavailable (" +
                                error.javaClass.getSimpleName() +
                                ")")
                        )
                    }
                }
            }
        var hooks: Int =
            if (proxy) 0 else XposedBridge.hookAllMethods(manager, "transfer", transfer).size
        hooks += XposedBridge.hookAllMethods(manager, "requestCreateSession", transfer).size
        XposedBridge.log("HomePod Bridge: system transfer hooks installed=" + hooks)
        XposedBridge.hookAllMethods(
            manager,
            if (proxy) "onSessionCreatedOnHandler" else "createSessionOnHandler",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val patternPrefix2 = param.hasThrowable() || param.args.size != 2
                    val session =
                        if (!patternPrefix2) param.args[1] as? RoutingSessionInfo else null
                    if (
                        patternPrefix2 ||
                            session == null ||
                            !SystemRouteTransfer.isBridgeSession(session)
                    )
                        return
                    try {
                        val pending =
                            SystemRouteTransfer.selections.get(session.getClientPackageName())
                        if (
                            pending != null &&
                                pending.manager === param.thisObject &&
                                SystemRouteTransfer.selections.remove(
                                    session.getClientPackageName(),
                                    pending,
                                )
                        )
                            XposedHelpers.callMethod(
                                param.thisObject,
                                if (proxy) "onTransferred" else "notifyTransferred",
                                pending.old,
                                session,
                            )
                    } catch (ignored: Throwable) {}
                }
            },
        )
        XposedBridge.hookAllMethods(
            manager,
            if (proxy) "onSessionReleasedOnHandler" else "notifySessionReleased",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val patternPrefix3 = param.hasThrowable() || param.args.size != 1
                    val old = if (!patternPrefix3) param.args[0] as? RoutingSessionInfo else null
                    if (patternPrefix3 || old == null || !SystemRouteTransfer.isBridgeSession(old))
                        return
                    try {
                        val stop = SystemRouteTransfer.stops.get(old.getId())
                        if (
                            stop != null &&
                                stop.manager === param.thisObject &&
                                SystemRouteTransfer.stops.remove(old.getId(), stop)
                        )
                            XposedHelpers.callMethod(
                                param.thisObject,
                                if (proxy) "onTransferred" else "notifyTransferred",
                                old,
                                stop.local,
                            )
                    } catch (ignored: Throwable) {}
                }
            },
        )
    }

    @JvmStatic
    private fun fail(source: String?, pending: SystemRouteTransfer.Pending) {
        if (!SystemRouteTransfer.selections.remove(source, pending)) return
        try {
            XposedHelpers.callMethod(
                pending.manager,
                if (pending.manager!!.javaClass.getName().contains("\$ProxyMediaRouter2Impl"))
                    "onTransferFailed"
                else "notifyTransferFailed",
                pending.old,
                pending.route,
            )
        } catch (ignored: Throwable) {}
    }

    @JvmStatic
    private fun localSession(manager: Any?, source: String?, proxy: Boolean): RoutingSessionInfo? {
        val sessions: Any? =
            if (proxy) XposedHelpers.callMethod(manager, "getRoutingSessions")
            else XposedHelpers.callMethod(manager, "getRoutingSessions", source)
        if (sessions is MutableList<*>)
            for (item in sessions) if (
                item is RoutingSessionInfo &&
                    XposedHelpers.callMethod(item, "isSystemSession") as Boolean
            )
                return item
        return null
    }

    @JvmStatic
    private fun isBridgeRoute(route: MediaRoute2Info): Boolean {
        return route.getId().startsWith("dev.ryuya.homepodbridge/") &&
            route.getFeatures().contains("dev.ryuya.homepodbridge.AIRPLAY")
    }

    @JvmStatic
    private fun isBridgeSession(session: RoutingSessionInfo): Boolean {
        return session.getId().startsWith("dev.ryuya.homepodbridge/")
    }

    @JvmStatic
    private fun control(method: String?, args: Bundle?, failure: Runnable) {
        Thread(
                Runnable {
                    try {
                        val result =
                            SystemRouteTransfer.context!!.getContentResolver()
                                .call(
                                    Uri.parse("content://" + RootProtocol.AUTHORITY),
                                    method!!,
                                    null,
                                    args,
                                )
                        if (result == null || !result.getBoolean("accepted")) failure.run()
                    } catch (error: Throwable) {
                        failure.run()
                    }
                },
                "HomePod-route",
            )
            .start()
    }

    @JvmRecord
    private data class Pending(
        val manager: Any?,
        val old: RoutingSessionInfo?,
        val route: MediaRoute2Info?,
    ) {
        override fun toString(): String = "Pending[manager=$manager, old=$old, route=$route]"
    }

    @JvmRecord
    private data class Stop(val manager: Any?, val local: RoutingSessionInfo?) {
        override fun toString(): String = "Stop[manager=$manager, local=$local]"
    }
}
