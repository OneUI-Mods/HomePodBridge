package dev.ryuya.homepodbridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.DiscoveryListener
import android.net.nsd.NsdManager.ServiceInfoCallback
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import android.os.Handler
import android.os.Looper
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.concurrent.Executor

class Discovery(context: Context, listener: Discovery.Listener) : AutoCloseable {
    interface Listener {
        fun changed(receivers: MutableList<Receiver?>?)

        fun failed()
    }

    private val nsd: NsdManager
    private val main: Handler = Handler(Looper.getMainLooper())
    private val executor: Executor = Executor { r: Runnable? -> main.post(r!!) }
    private val listener: Discovery.Listener
    private val receivers: MutableMap<String?, Receiver?> = LinkedHashMap<String?, Receiver?>()
    private val callbacks: MutableMap<String?, ServiceInfoCallback?> =
        LinkedHashMap<String?, ServiceInfoCallback?>()
    private var multicast: MulticastLock? = null
    private var active: Boolean = false
    private val discovery: DiscoveryListener =
        object : DiscoveryListener {
            override fun onDiscoveryStarted(type: String?) {}

            override fun onDiscoveryStopped(type: String?) {}

            override fun onStartDiscoveryFailed(type: String?, code: Int) {
                main.post(
                    Runnable {
                        close()
                        listener.failed()
                    }
                )
            }

            override fun onStopDiscoveryFailed(type: String?, code: Int) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                main.post(Runnable { watch(service) })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                main.post(Runnable { remove(service.getServiceName()) })
            }
        }

    init {
        this.listener = listener
        nsd = context.getSystemService<NsdManager?>(NsdManager::class.java)
        val wifi =
            context.getApplicationContext().getSystemService<WifiManager?>(WifiManager::class.java)
        if (wifi != null) {
            multicast = wifi.createMulticastLock("HomePodBridge")
            multicast!!.setReferenceCounted(false)
        }
    }

    fun start() {
        if (active) return
        active = true
        try {
            if (multicast != null) multicast!!.acquire()
            nsd.discoverServices("_raop._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: RuntimeException) {
            close()
            listener.failed()
        }
    }

    private fun watch(info: NsdServiceInfo) {
        if (!active || callbacks.containsKey(info.getServiceName())) return
        val key = info.getServiceName()
        val callback: ServiceInfoCallback =
            object : ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    callbacks.remove(key)
                }

                override fun onServiceInfoCallbackUnregistered() {}

                override fun onServiceLost() {
                    receivers.remove(key)
                    publish()
                }

                override fun onServiceUpdated(service: NsdServiceInfo) {
                    if (!active) return
                    var found: Receiver? = null
                    for (address in service.getHostAddresses()) {
                        found =
                            Receiver.from(key, address, service.getPort(), service.getAttributes())
                        if (found != null) break
                    }
                    if (found == null) receivers.remove(key) else receivers.put(key, found)
                    publish()
                }
            }
        callbacks.put(key, callback)
        try {
            nsd.registerServiceInfoCallback(info, executor, callback)
        } catch (e: RuntimeException) {
            callbacks.remove(key)
            listener.failed()
        }
    }

    private fun remove(key: String?) {
        val callback = callbacks.remove(key)
        if (callback != null)
            try {
                nsd.unregisterServiceInfoCallback(callback)
            } catch (ignored: RuntimeException) {}
        receivers.remove(key)
        publish()
    }

    private fun publish() {
        val unique = LinkedHashMap<String?, Receiver?>()
        for (receiver in receivers.values) unique.put(receiver!!.id, receiver)
        listener.changed(ArrayList<Receiver?>(unique.values))
    }

    override fun close() {
        if (!active) return
        active = false
        try {
            nsd.stopServiceDiscovery(discovery)
        } catch (ignored: RuntimeException) {}
        for (callback in ArrayList<ServiceInfoCallback?>(callbacks.values)) {
            try {
                nsd.unregisterServiceInfoCallback(callback!!)
            } catch (ignored: RuntimeException) {}
        }
        callbacks.clear()
        receivers.clear()
        if (multicast != null && multicast!!.isHeld()) multicast!!.release()
        publish()
    }
}
