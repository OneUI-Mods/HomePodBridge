package dev.ryuya.homepodbridge;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

final class Discovery implements AutoCloseable {
    interface Listener { void changed(List<Receiver> receivers); void failed(); }
    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor executor = main::post;
    private final Listener listener;
    private final Map<String, Receiver> receivers = new LinkedHashMap<>();
    private final Map<String, NsdManager.ServiceInfoCallback> callbacks = new LinkedHashMap<>();
    private WifiManager.MulticastLock multicast;
    private boolean active;
    private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
        public void onDiscoveryStarted(String type) {}
        public void onDiscoveryStopped(String type) {}
        public void onStartDiscoveryFailed(String type, int code) { main.post(() -> { close(); listener.failed(); }); }
        public void onStopDiscoveryFailed(String type, int code) {}
        public void onServiceFound(NsdServiceInfo service) { main.post(() -> watch(service)); }
        public void onServiceLost(NsdServiceInfo service) { main.post(() -> remove(service.getServiceName())); }
    };
    Discovery(Context context, Listener listener) {
        this.listener = listener;
        nsd = context.getSystemService(NsdManager.class);
        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        if (wifi != null) { multicast = wifi.createMulticastLock("HomePodBridge"); multicast.setReferenceCounted(false); }
    }
    void start() {
        if (active) return;
        active = true;
        try {
            if (multicast != null) multicast.acquire();
            nsd.discoverServices("_raop._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) { close(); listener.failed(); }
    }
    private void watch(NsdServiceInfo info) {
        if (!active || callbacks.containsKey(info.getServiceName())) return;
        String key = info.getServiceName();
        NsdManager.ServiceInfoCallback callback = new NsdManager.ServiceInfoCallback() {
            public void onServiceInfoCallbackRegistrationFailed(int errorCode) { callbacks.remove(key); }
            public void onServiceInfoCallbackUnregistered() {}
            public void onServiceLost() { receivers.remove(key); publish(); }
            public void onServiceUpdated(NsdServiceInfo service) {
                if (!active) return;
                Receiver found = null;
                for (InetAddress address : service.getHostAddresses()) {
                    found = Receiver.from(key, address, service.getPort(), service.getAttributes());
                    if (found != null) break;
                }
                if (found == null) receivers.remove(key); else receivers.put(key, found);
                publish();
            }
        };
        callbacks.put(key, callback);
        try { nsd.registerServiceInfoCallback(info, executor, callback); }
        catch (RuntimeException e) { callbacks.remove(key); listener.failed(); }
    }
    private void remove(String key) {
        NsdManager.ServiceInfoCallback callback = callbacks.remove(key);
        if (callback != null) try { nsd.unregisterServiceInfoCallback(callback); } catch (RuntimeException ignored) {}
        receivers.remove(key);
        publish();
    }
    private void publish() {
        Map<String, Receiver> unique = new LinkedHashMap<>();
        for (Receiver receiver : receivers.values()) unique.put(receiver.id(), receiver);
        listener.changed(new ArrayList<>(unique.values()));
    }
    public void close() {
        if (!active) return;
        active = false;
        try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) {}
        for (NsdManager.ServiceInfoCallback callback : new ArrayList<>(callbacks.values())) {
            try { nsd.unregisterServiceInfoCallback(callback); } catch (RuntimeException ignored) {}
        }
        callbacks.clear();
        receivers.clear();
        if (multicast != null && multicast.isHeld()) multicast.release();
        publish();
    }
}
