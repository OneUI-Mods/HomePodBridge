package dev.ryuya.homepodbridge.compat

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field

/** Aliases only the private volume UI. Android's audio/Binder implementation stays untouched. */
internal object VolumeUiSymbols {
    fun findClass(name: String, loader: ClassLoader?): Class<*> =
        DexSymbols.findClass(name, false, loader)

    fun findClassIfExists(name: String, loader: ClassLoader?): Class<*>? =
        try {
            findClass(name, loader)
        } catch (_: ClassNotFoundException) {
            null
        }

    fun findAndHookMethod(owner: Class<*>, name: String, vararg args: Any?): XC_MethodHook.Unhook {
        val callback = args.last() as XC_MethodHook
        val params = args.dropLast(1).map { it as Class<*> }.toTypedArray()
        val method = owner.compatibleDeclaredMethod(name, *params).apply { isAccessible = true }
        return XposedBridge.hookMethod(method, callback)
    }

    fun hookAllMethods(
        owner: Class<*>,
        name: String,
        callback: XC_MethodHook,
    ): Set<XC_MethodHook.Unhook> =
        owner.declaredMethods
            .filter { it.compatibleName() == name }
            .mapTo(linkedSetOf()) {
                XposedBridge.hookMethod(it, callback)
            }

    private fun field(owner: Class<*>, name: String): Field {
        var current: Class<*>? = owner
        while (current != null) {
            try {
                return current.compatibleDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    fun getObjectField(owner: Any?, name: String): Any? = field(owner!!.javaClass, name).get(owner)

    fun getStaticObjectField(owner: Class<*>, name: String): Any? = field(owner, name).get(null)

    fun getStaticIntField(owner: Class<*>, name: String): Int = field(owner, name).getInt(null)

    fun callMethod(owner: Any?, name: String, vararg args: Any?): Any? =
        XposedHelpers.callMethod(
            owner,
            DexSymbols.invocationName(owner!!.javaClass, name, args.size),
            *args,
        )

    fun newInstance(owner: Class<*>, vararg args: Any?): Any =
        XposedHelpers.newInstance(owner, *args)

    fun callStaticMethod(owner: Class<*>, name: String, vararg args: Any?): Any? =
        XposedHelpers.callStaticMethod(
            owner,
            DexSymbols.invocationName(owner, name, args.size),
            *args,
        )
}
