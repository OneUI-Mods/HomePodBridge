package dev.ryuya.homepodbridge

import android.media.AudioManager
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import dev.ryuya.homepodbridge.compat.VolumeUiSymbols
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Redirects only the active whole-device lease's local-media panel to its remote session. */
object SystemVolumeUi {
    @JvmField @Volatile var active: SystemVolumeEndpoint? = null
    @JvmField @Volatile var available: Boolean = false

    @JvmStatic
    fun install(loader: ClassLoader?) {
        try {
            val controller =
                VolumeUiSymbols.findClass(
                    "com.android.systemui.volume.VolumeDialogControllerImpl",
                    loader,
                )
            VolumeUiSymbols.findAndHookMethod(
                controller,
                "onVolumeChangedW",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val endpoint = SystemVolumeUi.active
                        if (endpoint == null || param.args[0] as Int? != AudioManager.STREAM_MUSIC)
                            return
                        if (endpoint.redirectLocalUi(param.thisObject, param.args[1] as Int))
                            param.setResult(false)
                    }
                },
            )
            val callbacks =
                VolumeUiSymbols.findClass(
                    "com.android.systemui.volume.VolumeDialogControllerImpl\$MediaSessionsCallbacks",
                    loader,
                )
            VolumeUiSymbols.hookAllMethods(
                callbacks,
                "onRemoteUpdate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val endpoint = SystemVolumeUi.active
                        if (endpoint != null && param.args.size == 3) {
                            val name = endpoint.receiverNameFor(param.args[0])
                            if (name != null) param.args[1] = name
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val endpoint = SystemVolumeUi.active
                        if (endpoint != null && param.args.size == 3 && !param.hasThrowable())
                            endpoint.rememberRemoteStream(param.thisObject, param.args[0])
                    }
                },
            )
            SystemVolumeUi.available = true
            val reducer =
                VolumeUiSymbols.findClassIfExists(
                    "com.android.systemui.volume.reducer.VolumePanelReducer",
                    loader,
                )
            if (reducer != null) {
                VolumeUiSymbols.hookAllMethods(
                    reducer,
                    "reduce",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val endpoint = SystemVolumeUi.active
                            if (endpoint == null || param.args.size != 2) return
                            val handled = endpoint.handleIconAction(param.args[0], param.args[1])
                            if (handled != null) param.setResult(handled)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val endpoint = SystemVolumeUi.active
                            if (endpoint != null && !param.hasThrowable())
                                param.setResult(endpoint.enableIconState(param.getResult()))
                        }
                    },
                )
                Log.i("HomePodBridgeUi", "Volume reducer integration loaded")
            }
        } catch (error: Throwable) {
            XposedBridge.log(
                ("HomePod Bridge: remote volume panel unavailable (" +
                    error.javaClass.getSimpleName() +
                    ")")
            )
        }
    }
}
