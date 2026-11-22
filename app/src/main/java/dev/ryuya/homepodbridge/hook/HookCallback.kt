package dev.ryuya.homepodbridge.hook

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/** Before/after callbacks with the same failure isolation as the Java adapter. */
abstract class HookCallback {
    @Throws(Throwable::class) protected open fun beforeHookedMethod(param: MethodHookParam) {}

    @Throws(Throwable::class) protected open fun afterHookedMethod(param: MethodHookParam) {}

    class MethodHookParam {
        @JvmField var method: Executable? = null
        @JvmField var thisObject: Any? = null
        @JvmField var args: Array<Any?> = emptyArray()
        @kotlin.jvm.JvmField internal var result: Any? = null
        @kotlin.jvm.JvmField internal var throwable: Throwable? = null
        @kotlin.jvm.JvmField internal var skip = false
        fun getResult(): Any? = result

        fun getThrowable(): Throwable? = throwable

        fun hasThrowable(): Boolean = throwable != null

        fun setResult(value: Any?) {
            result = value
            throwable = null
            skip = true
        }

        fun setThrowable(value: Throwable?) {
            throwable = value
            result = null
            skip = true
        }


    }

    @Throws(Throwable::class)
    fun intercept(chain: XposedInterface.Chain): Any? {
        val param =
            MethodHookParam().apply {
                method = chain.executable
                thisObject = chain.thisObject
                args = chain.args.toTypedArray()
            }
        try {
            beforeHookedMethod(param)
        } catch (error: Throwable) {
            Hooks.log(error)
            param.skip = false
            param.result = null
            param.throwable = null
        }
        if (!param.skip) {
            try {
                param.result = chain.proceed(param.args)
            } catch (error: Throwable) {
                param.throwable = error
            }
        }
        val result = param.result
        val throwable = param.throwable
        try {
            afterHookedMethod(param)
        } catch (error: Throwable) {
            Hooks.log(error)
            param.result = result
            param.throwable = throwable
        }
        param.throwable?.let { throw it }
        return param.result
    }

    class Unhook(private val handle: XposedInterface.HookHandle) {
        fun unhook() {
            handle.unhook()
        }
    }
}
