package dev.ryuya.homepodbridge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.os.Process
import android.system.Os
import android.util.Log
import java.io.File
import java.nio.file.Files
import kotlin.jvm.JvmStatic

/* Creates only the official CLI's missing SystemUI private directory through Android's Context. */
object GalaxyReloadPrepare {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (args.size != 0 || Process.myUid() != 0) throw SecurityException()
            if (Looper.myLooper() == null) Looper.prepare()
            val activity = Class.forName("android.app.ActivityThread")
            val thread = activity.getMethod("systemMain").invoke(null)
            val context: Context? =
                activity.getMethod("getSystemContext").invoke(thread) as Context?
            val app = context!!.getPackageManager().getApplicationInfo("com.android.systemui", 0)
            if (
                (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    app.uid < 1000 ||
                    app.uid >= 100000
            )
                throw SecurityException()
            val expected = File("/data/user_de/0/com.android.systemui/files")
            val parent = expected.getParentFile()
            if (
                (parent.getPath() != app.deviceProtectedDataDir) ||
                    Files.isSymbolicLink(parent.toPath()) ||
                    Files.isSymbolicLink(expected.toPath())
            )
                throw SecurityException()
            if (!parent.exists()) {
                Files.createDirectory(parent.toPath())
                Os.chown(parent.getPath(), app.uid, app.uid)
                Os.chmod(parent.getPath(), 448)
            }
            if (
                !parent.isDirectory() ||
                    ((Files.getAttribute(parent.toPath(), "unix:uid") as Number).toInt() != app.uid)
            )
                throw SecurityException()
            val existed = expected.exists()
            val target =
                context!!
                    .createPackageContext("com.android.systemui", 0)
                    .createDeviceProtectedStorageContext()
                    .getFilesDir()
            if (target.getCanonicalPath() != expected.getPath() || !target.isDirectory())
                throw SecurityException()
            if (!existed) {
                Os.chown(target.getPath(), app.uid, app.uid)
                Os.chmod(target.getPath(), 448)
            }
            if ((Files.getAttribute(target.toPath(), "unix:uid") as Number).toInt() != app.uid)
                throw SecurityException()
            println("PREPARED")
            System.out.flush()
            System.exit(0)
        } catch (unavailable: Exception) {
            Log.e("HomePodBridgeReload", "Private CLI directory unavailable", unavailable)
            println("UNAVAILABLE")
            System.out.flush()
            System.exit(2)
        }
    }
}
