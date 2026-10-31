package dev.ryuya.homepodbridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager.NameNotFoundException
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException

class SystemBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        try {
            val systemUi =
                getContext()!!.getPackageManager().getApplicationInfo("com.android.systemui", 0).uid
            if (Binder.getCallingUid() != systemUi)
                throw SecurityException("Only SystemUI can control routes")
            if ("register" == method && extras != null) {
                RootConnector.register(extras.getBinder("bridge"))
                return Bundle.EMPTY
            }
            val main = Handler(Looper.getMainLooper())
            if ("select" == method && extras != null) {
                val source = extras.getString("source")
                val route = extras.getString("route")
                require(
                    !(source == null ||
                        source.isEmpty() ||
                        route == null ||
                        !route.startsWith("homepod-"))
                ) {
                    "Invalid route selection"
                }
                main.post(Runnable { HomePodRoutes.selectFromSystem(source, route) })
            } else if ("stop" == method) {
                main.post(Runnable { HomePodRoutes.stopFromSystem() })
            } else throw IllegalArgumentException("Unknown call")
            val result = Bundle()
            result.putBoolean("accepted", true)
            return result
        } catch (e: NameNotFoundException) {
            throw IllegalStateException("System bridge registration failed")
        } catch (e: RemoteException) {
            throw IllegalStateException("System bridge registration failed")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        sort: String?,
    ): Cursor? {
        throw UnsupportedOperationException()
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException()
    }

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        throw UnsupportedOperationException()
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        args: Array<out String>?,
    ): Int {
        throw UnsupportedOperationException()
    }
}
