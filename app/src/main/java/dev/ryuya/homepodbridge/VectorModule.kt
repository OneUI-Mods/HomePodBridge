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
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import dev.ryuya.homepodbridge.hook.HookCallback
import dev.ryuya.homepodbridge.hook.HookCallback.MethodHookParam
import dev.ryuya.homepodbridge.hook.Hooks
import dev.ryuya.homepodbridge.hook.Reflect
import dev.ryuya.homepodbridge.compat.DexSymbols
import dev.ryuya.homepodbridge.startup.FirstUnlockGate
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayList
import java.util.LinkedHashMap
import kotlin.jvm.JvmStatic
import kotlin.math.log

/** Publishes Bridge-owned routes inside the scoped SystemUI process. */
class VectorModule : XposedModule() {
    private val firstUnlock = FirstUnlockGate()
    private val main = Handler(Looper.getMainLooper())
    private var processName: String? = null
    private var host: Context? = null
    private var hostLoader: ClassLoader? = null
    private var registration: BridgeRegistration? = null
    private var volumePrepared = false
    private var recoveryState: Array<*>? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        Hooks.attach(this)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SYSTEM_UI || processName != SYSTEM_UI || !param.isFirstPackage) return
        val loader = param.classLoader
        val bootstrap = arrayOfNulls<HookCallback.Unhook>(1)
        bootstrap[0] = Reflect.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : HookCallback() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = param.args[0] as? Context ?: return
                    if (param.hasThrowable() || context.packageName != SYSTEM_UI) return
                    bootstrap[0]?.unhook()
                    firstUnlock.run(context) {
                        SystemRouteTransfer.context = context
                        DexSymbols.whenPrepared(context) {
                            SystemVolumeUi.install(loader)
                            volumePrepared = true
                        }
                        runCatching {
                            startRegistration(context, loader, SystemAudioBridge(context, moduleApplicationInfo.sourceDir))
                        }.onFailure { Hooks.log(it) }
                    }
                }
            },
        )
        installRouteHooks(loader)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        if (processName != SYSTEM_UI) return false
        return MainThreadReload.run(main) {
            val current = registration
            if (current == null) {
                val fallback = recoveryState ?: return@run false
                param.setSavedInstanceState(fallback)
                DexSymbols.detachObserver()
                recoveryState = null
                return@run true
            }
            if (!volumePrepared || current.registering.get()) return@run false
            if (!Hooks.tryRetire {
                    SystemRouteTransfer.readyForHotReload() && current.bridge.tryRetire {
                        param.setSavedInstanceState(arrayOf(host, hostLoader))
                    }
                }) return@run false
            current.close()
            registration = null
            SystemRouteTransfer.context = null
            DexSymbols.detachObserver()
            true
        }
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val state = requireNotNull(param.savedInstanceState as? Array<*>)
        val context = requireNotNull(state.getOrNull(0) as? Context)
        val loader = requireNotNull(state.getOrNull(1) as? ClassLoader)
        check(context.packageName == SYSTEM_UI && param.processName == SYSTEM_UI)
        onModuleLoaded(param)
        recoveryState = state
        val bridge = SystemAudioBridge(context, moduleApplicationInfo.sourceDir)
        check(MainThreadReload.run(main) {
            check(DexSymbols.canInstallNow(context)) { "Volume UI discovery is still preparing" }
            installRouteHooks(loader)
            SystemVolumeUi.install(loader)
            volumePrepared = true
            SystemRouteTransfer.context = context
            param.oldHookHandles.forEach { it.unhook() }
            startRegistration(context, loader, bridge) {
                Hooks.log("ONEUI_MODS_HOT_RELOAD package=${BuildConfig.APPLICATION_ID} " +
                    "version=${BuildConfig.VERSION_CODE} pid=${Process.myPid()}")
            }
            recoveryState = null
            true
        }) { "SystemUI main thread was unavailable" }
    }

    private fun startRegistration(
        context: Context,
        loader: ClassLoader,
        bridge: SystemAudioBridge,
        onRegistered: (() -> Unit)? = null,
    ) {
        val owner = BridgeRegistration(context, bridge, onRegistered)
        context.registerReceiver(owner, IntentFilter(RootProtocol.REQUEST), Context.RECEIVER_EXPORTED)
        registration = owner
        host = context
        hostLoader = loader
        owner.request()
    }

    private fun installRouteHooks(loader: ClassLoader) {
        val manager =
            Reflect.findClassIfExists("android.media.MediaRouter2Manager", loader)
        if (manager == null) return
        SystemRouteTransfer.install(manager)
        val proxy =
            Reflect.findClassIfExists(
                "android.media.MediaRouter2\$ProxyMediaRouter2Impl",
                loader,
            )
        if (proxy != null) SystemRouteTransfer.install(proxy)
        val includeBridge: HookCallback =
            object : HookCallback() {
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
                        val all = Reflect.callMethod(param.thisObject, "getAllRoutes")
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
        Hooks.hookAllMethods(manager, "getAvailableRoutes", includeBridge)
        Hooks.hookAllMethods(manager, "getTransferableRoutes", includeBridge)
    }

    private class BridgeRegistration(
        private val context: Context,
        val bridge: SystemAudioBridge,
        private var onRegistered: (() -> Unit)?,
    ) : BroadcastReceiver() {
        val registering = AtomicBoolean(false)
        private val main = Handler(Looper.getMainLooper())
        private val retry = Runnable { request() }
        private var lastRequest = 0L
        private var attempts = 0
        private var closed = false

        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRequest < 1000) return
            lastRequest = now
            if (!registering.get()) {
                attempts = 0
                request()
            }
        }

        fun request() {
            if (closed || !registering.compareAndSet(false, true)) return
            main.removeCallbacks(retry)
            val attempt = ++attempts
            Thread({
                var retryNeeded = false
                try {
                    val data = Bundle().apply { putBinder("bridge", bridge) }
                    val result = context.contentResolver.call(
                        Uri.parse("content://" + RootProtocol.AUTHORITY), "register", null, data)
                    checkNotNull(result) { "Provider did not register the bridge" }
                    Hooks.log("HomePod Bridge: system audio bridge registered")
                    onRegistered?.invoke()
                    onRegistered = null
                } catch (error: Exception) {
                    Hooks.log("HomePod Bridge: system audio bridge unavailable (${error.javaClass.simpleName})")
                    retryNeeded = attempt < 5
                } finally {
                    if (retryNeeded) main.postDelayed(retry, attempt * 500L)
                    registering.set(false)
                }
            }, "HomePod-register").start()
        }

        fun close() {
            closed = true
            main.removeCallbacks(retry)
            runCatching { context.unregisterReceiver(this) }.onFailure(Hooks::log)
        }
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"

        @JvmStatic
        private fun providerPackage(route: MediaRoute2Info?): String? {
            try {
                return Reflect.callMethod(route, "getProviderPackageName") as String?
            } catch (e: NoSuchMethodError) {
                return Reflect.callMethod(route, "getPackageName") as String?
            }
        }
    }
}
