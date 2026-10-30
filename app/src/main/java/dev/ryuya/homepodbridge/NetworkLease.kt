package dev.ryuya.homepodbridge

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.PowerManager
import kotlin.jvm.JvmStatic

/* Public, session-owned Wi-Fi request. Held is not evidence of radio/driver activation. */
class NetworkLease(context: Context, requested: Boolean) : AutoCloseable {
    private var lock: WifiLock? = null
    private var unavailable: Int = 0

    init {
        initialize(context, requested)
    }

    private fun initialize(context: Context, requested: Boolean) {
        if (!requested) return
        if (!NetworkLease.onWifi(context)) {
            unavailable = 3
            return
        }
        try {
            val wifi =
                context
                    .getApplicationContext()
                    .getSystemService<WifiManager?>(WifiManager::class.java)
            if (wifi != null) {
                lock =
                    wifi.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                        "HomePodBridge:audio",
                    )
                lock!!.setReferenceCounted(false)
                lock!!.acquire()
            }
        } catch (denied: SecurityException) {
            close()
            unavailable = 4
        } catch (unsupported: RuntimeException) {
            close()
            unavailable = 5
        }
    }

    fun status(context: Context): Int {
        if (lock == null || !lock!!.isHeld()) return unavailable
        val process = RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        val eligible: Boolean =
            NetworkLease.onWifi(context) &&
                context.getSystemService<PowerManager?>(PowerManager::class.java).isInteractive() &&
                process.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        return if (eligible) 2 else 1
    }

    override fun close() {
        val current: WifiLock? = lock
        lock = null
        if (current != null && current.isHeld()) {
            try {
                current.release()
            } catch (ignored: RuntimeException) {}
        }
    }

    companion object {
        @JvmStatic
        private fun onWifi(context: Context): Boolean {
            val manager =
                context.getSystemService<ConnectivityManager?>(ConnectivityManager::class.java)
            var network = manager.getBoundNetworkForProcess()
            if (network == null) network = manager.getActiveNetwork()
            val capabilities: NetworkCapabilities? =
                if (network == null) null else manager.getNetworkCapabilities(network)
            return capabilities != null &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }
}
