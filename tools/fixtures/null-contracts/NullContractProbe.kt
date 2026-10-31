package dev.ryuya.nullprobe

import android.content.ContentProvider
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Looper
import dalvik.system.PathClassLoader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/** Detached, silent release-APK checks. Invalid sources are rejected before any audio work. */
object NullContractProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        if (Build.VERSION.SDK_INT >= 37) {
            val type = Class.forName("com.android.internal.os.ApplicationSharedMemory")
            val shared = type.getDeclaredMethod("create").invoke(null)
            type.declaredFields
                .single { it.type == type && Modifier.isStatic(it.modifiers) }
                .apply { isAccessible = true }
                .set(null, shared)
        }
        val thread = Class.forName("android.app.ActivityThread")
        val instance = thread.getDeclaredMethod("systemMain").invoke(null)
        val context = thread.getDeclaredMethod("getSystemContext").invoke(instance) as Context
        val home = PathClassLoader(args[0], NullContractProbe::class.java.classLoader)
        val bridge = PathClassLoader(args[1], NullContractProbe::class.java.classLoader)
        val before = args.getOrNull(2) == "before"
        val provider =
            Class.forName("dev.ryuya.oneuihometweaks.ConfigProvider", true, home)
                .getConstructor()
                .newInstance() as ContentProvider
        ContentProvider::class
            .java
            .getDeclaredField("mContext")
            .apply { isAccessible = true }
            .set(provider, context)
        fun failure(block: () -> Unit): Throwable {
            try {
                block()
            } catch (e: Throwable) {
                return if (e is InvocationTargetException) e.cause!! else e
            }
            error("Invalid input was accepted")
        }
        val journal = failure { provider.call("layout_append", null, Bundle()) }
        check(
            if (before) journal is NullPointerException
            else journal is IllegalStateException && journal.cause is java.io.IOException
        ) {
            journal
        }
        println("PASS journal missing records: ${journal.javaClass.simpleName}")
        val noBundle = failure { provider.call("layout_append", null, null) }
        check(noBundle is IllegalStateException && noBundle.cause is java.io.IOException)
        println("PASS absent journal bundle still reaches bounded input validation")

        val timing = Class.forName("dev.ryuya.homepodbridge.halprobe.PhoneTiming", true, bridge)
        val input = Class.forName("dev.ryuya.homepodbridge.RootHalInput", true, bridge)
        val constructor =
            input.getConstructor(
                Context::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                timing,
            )
        val normal = failure {
            constructor.newInstance(context, "!invalid", false, true, false, null)
        }
        if (before) check(normal is NullPointerException) { normal }
        else {
            check(
                normal.javaClass.simpleName == "StartupException" &&
                    normal.cause is SecurityException
            ) {
                normal
            }
            check(normal.javaClass.getField("safeFallback").getBoolean(normal))
        }
        println("PASS absent timing: ${normal.javaClass.simpleName}")
        val fastest = timing.getMethod("fastest").invoke(null)
        val explicit = failure {
            constructor.newInstance(context, "!invalid", false, true, false, fastest)
        }
        check(
            explicit.javaClass.simpleName == "StartupException" &&
                explicit.cause is SecurityException
        )
        check(explicit.javaClass.getField("safeFallback").getBoolean(explicit))
        println("PASS explicit timing still follows the same safe startup guard")
        val state = Class.forName("dev.ryuya.homepodbridge.halprobe.ReadOnlyHalState", true, bridge)
        val absent = failure { state.getConstructor().newInstance() }
        check(if (before) absent is NullPointerException else absent is SecurityException) {
            absent
        }
        println("PASS absent vendor HAL: ${absent.javaClass.simpleName}")
        val connector = Class.forName("dev.ryuya.homepodbridge.RootConnector", true, bridge)
        val missing = failure {
            connector.getMethod("register", android.os.IBinder::class.java).invoke(null, null)
        }
        check(missing is IllegalArgumentException && missing.message == "Missing system bridge")
        println("PASS missing bridge remains rejected before route or audio activation")

        // A detached provider fixture, not an IPC request or an installed host process. Give only
        // this thread the expected incoming Binder identity, restoring it before leaving the test.
        val systemProvider =
            Class.forName("dev.ryuya.homepodbridge.SystemBridgeProvider", true, bridge)
                .getConstructor()
                .newInstance() as ContentProvider
        ContentProvider::class
            .java
            .getDeclaredField("mContext")
            .apply { isAccessible = true }
            .set(systemProvider, context)
        val systemUid = context.packageManager.getApplicationInfo("com.android.systemui", 0).uid
        val identity = Binder.clearCallingIdentity()
        val rejected =
            try {
                Binder.restoreCallingIdentity(
                    (systemUid.toLong() shl 32) or android.os.Process.myPid().toLong()
                )
                failure { systemProvider.call("register", null, Bundle()) }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        check(
            if (before) rejected is NullPointerException
            else rejected is IllegalArgumentException && rejected.message == "Missing system bridge"
        ) {
            rejected
        }
        println("PASS detached provider missing bridge: ${rejected.javaClass.simpleName}")
    }
}
