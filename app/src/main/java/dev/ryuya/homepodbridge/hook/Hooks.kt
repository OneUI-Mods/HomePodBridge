package dev.ryuya.homepodbridge.hook

import dev.ryuya.homepodbridge.compat.compatibleName
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Hooks belong to the currently attached API 102 framework instance. */
object Hooks {
    @Volatile private var framework: XposedInterface? = null
    private val calls = ReentrantReadWriteLock()
    @Volatile private var retired = false

    fun tryRetire(save: () -> Boolean): Boolean {
        val lock = calls.writeLock()
        if (!lock.tryLock()) return false
        try {
            if (!save()) return false
            retired = true
            return true
        } finally {
            lock.unlock()
        }
    }

    @JvmStatic
    fun attach(api: XposedInterface) {
        check(api.apiVersion >= 102) { "libxposed API 102 required" }
        framework = api
        api.log(4, "HomePodBridge", "libxposed API 102 attached")
    }

    @kotlin.jvm.JvmStatic
    private fun api(): XposedInterface =
        checkNotNull(framework) { "Modern hook framework not attached" }

    @JvmStatic
    fun hook(method: Executable, callback: HookCallback): HookCallback.Unhook =
        HookCallback.Unhook(
            api()
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    if (retired) chain.proceed()
                    else {
                        val lock = calls.readLock()
                        lock.lock()
                        try {
                            if (retired) chain.proceed() else callback.intercept(chain)
                        } finally {
                            lock.unlock()
                        }
                    }
                }
        )

    @JvmStatic
    fun hookAllMethods(
        type: Class<*>,
        name: String?,
        callback: HookCallback,
    ): MutableSet<HookCallback.Unhook> {
        val installed = linkedSetOf<HookCallback.Unhook>()
        try {
            for (method in type.declaredMethods) if (method.compatibleName() == name)
                installed += hook(method, callback)
            return installed
        } catch (error: Throwable) {
            installed.forEach { it.unhook() }
            throw error
        }
    }

    @JvmStatic
    fun log(message: String) {
        framework?.log(4, "HomePodBridge", message)
    }

    @JvmStatic
    fun log(error: Throwable) {
        framework?.log(6, "HomePodBridge", error.toString(), error)
    }
}
