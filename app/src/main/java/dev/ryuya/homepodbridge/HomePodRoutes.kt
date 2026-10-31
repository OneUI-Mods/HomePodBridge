package dev.ryuya.homepodbridge

import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRoute2Info
import android.media.MediaRoute2ProviderService
import android.media.RouteDiscoveryPreference
import android.media.RoutingSessionInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayList
import java.util.HashSet
import kotlin.jvm.JvmStatic

class HomePodRoutes : MediaRoute2ProviderService(), BridgeState.Observer {
    private val main: Handler = Handler(Looper.getMainLooper())
    private var discovery: Discovery? = null
    private var session: RoutingSessionInfo? = null
    private var pending: Long = MediaRoute2ProviderService.REQUEST_ID_NONE
    private var sequence: Long = 0
    private var creating: Boolean = false
    private var destroyed: Boolean = false
    private val lastIds: MutableSet<String?> = HashSet<String?>()

    override fun onCreate() {
        super.onCreate()
        HomePodRoutes.active = this
        Diagnostics.route(this, "provider created")
        RootConnector.request(this)
        BridgeState.observe(this)
    }

    override fun onDiscoveryPreferenceChanged(preference: RouteDiscoveryPreference) {
        var added: Boolean = false
        for (feature in preference.getPreferredFeatures()) {
            if (
                feature.startsWith("com.google.android.gms.cast.CATEGORY_") &&
                    BridgeState.castCategories.size < 32
            )
                added = added or BridgeState.castCategories.add(feature)
        }
        if (added) BridgeState.publish()
        if (preference.getPreferredFeatures().isEmpty()) {
            if (discovery != null && BridgeState.capture == null) discovery!!.close()
            return
        }
        if (discovery == null) {
            discovery =
                Discovery(
                    this,
                    object : Discovery.Listener {
                        override fun changed(receivers: MutableList<Receiver?>?) {
                            BridgeState.receivers = receivers
                            BridgeState.publish()
                        }

                        override fun failed() {
                            BridgeState.status(R.string.scan_failed)
                        }
                    },
                )
        }
        discovery!!.start()
    }

    override fun changed() {
        if (destroyed) return
        val routes = ArrayList<MediaRoute2Info?>()
        lastIds.clear()
        for (receiver in BridgeState.receivers!!) {
            lastIds.add(receiver!!.id)
            val builder =
                android.media.MediaRoute2Info.Builder(receiver!!.id!!, receiver!!.name!!)
                    .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
                    .addFeature(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK)
                    .addFeature(MediaRoute2Info.FEATURE_REMOTE_AUDIO_PLAYBACK)
                    .addFeature(HomePodRoutes.FEATURE)
                    .setType(MediaRoute2Info.TYPE_REMOTE_SPEAKER)
                    .setDescription(getString(R.string.route_description))
                    .setConnectionState(
                        if (BridgeState.connected && receiver == BridgeState.selected)
                            MediaRoute2Info.CONNECTION_STATE_CONNECTED
                        else MediaRoute2Info.CONNECTION_STATE_DISCONNECTED
                    )
                    .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                    .setVolumeMax(100)
                    .setVolume(BridgeState.volume)
            for (category in BridgeState.castCategories) builder.addFeature(category!!)
            // AndroidX drops framework routes lacking its descriptor metadata before a chooser sees
            // them.
            val extras = Bundle()
            extras.putBundle("androidx.mediarouter.media.KEY_EXTRAS", Bundle.EMPTY)
            extras.putInt("androidx.mediarouter.media.KEY_DEVICE_TYPE", 2)
            extras.putInt("androidx.mediarouter.media.KEY_PLAYBACK_TYPE", 1)
            extras.putString("androidx.mediarouter.media.KEY_ORIGINAL_ROUTE_ID", receiver!!.id)
            val filters = ArrayList<IntentFilter?>()
            for (category in BridgeState.castCategories) {
                val filter = IntentFilter()
                filter.addCategory(category)
                filters.add(filter)
            }
            extras.putParcelableArrayList("androidx.mediarouter.media.KEY_CONTROL_FILTERS", filters)
            builder.setExtras(extras)
            routes.add(builder.build())
        }
        notifyRoutes(routes)
        if (session != null && session!!.getVolume() != BridgeState.volume) {
            session =
                android.media.RoutingSessionInfo.Builder(session!!)
                    .setVolume(BridgeState.volume)
                    .build()
            notifySessionUpdated(session!!)
        }
        if (
            session != null &&
                !creating &&
                (!BridgeState.connected ||
                    BridgeState.selected == null ||
                    !lastIds.contains(BridgeState.selected!!.id))
        ) {
            val old: RoutingSessionInfo? = session
            session = null
            notifySessionReleased(old!!.getId())
        }
    }

    override fun onCreateSession(
        requestId: Long,
        packageName: String,
        routeId: String,
        hints: Bundle?,
    ) {
        Diagnostics.route(this, "create " + requestId + " capture=" + (BridgeState.capture != null))
        if (packageName == null || packageName.isEmpty() || BridgeState.PACKAGE == packageName) {
            notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_REJECTED)
            return
        }
        val receiver =
            BridgeState.receivers!!.stream()
                .filter { r: Receiver? -> r!!.id.equals(routeId) }
                .findFirst()
                .orElse(null)
        if (receiver == null) {
            notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_REJECTED)
            prompt()
            return
        }
        if (BridgeState.capture == null) {
            RootConnector.request(this)
            if (RootConnector.available()) {
                try {
                    startForegroundService(
                        Intent(this, CaptureService::class.java).putExtra("system", true)
                    )
                    main.postDelayed(
                        Runnable {
                            Diagnostics.route(
                                this,
                                ("capture wait complete destroyed=" +
                                    destroyed +
                                    " capture=" +
                                    (BridgeState.capture != null)),
                            )
                            if (destroyed) return@Runnable
                            if (BridgeState.capture != null)
                                onCreateSession(requestId, packageName, routeId, hints)
                            else {
                                notifyRequestFailed(
                                    requestId,
                                    MediaRoute2ProviderService.REASON_REJECTED,
                                )
                                prompt()
                            }
                        },
                        200,
                    )
                    return
                } catch (ignored: RuntimeException) {}
            }
            notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_REJECTED)
            prompt()
            return
        }
        if (pending != MediaRoute2ProviderService.REQUEST_ID_NONE)
            notifyRequestFailed(pending, MediaRoute2ProviderService.REASON_REJECTED)
        pending = requestId
        val token: Long = ++sequence
        creating = true
        BridgeState.capture!!.select(
            receiver,
            packageName,
            CaptureService.ConnectionCallback { success: Boolean ->
                Diagnostics.route(this, "native ready=" + success + " sequence=" + token)
                if (destroyed || sequence != token) return@ConnectionCallback
                creating = false
                pending = MediaRoute2ProviderService.REQUEST_ID_NONE
                if (!success) {
                    notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_NETWORK_ERROR)
                    changed()
                    return@ConnectionCallback
                }
                if (session != null) notifySessionReleased(session!!.getId())
                session =
                    android.media.RoutingSessionInfo.Builder("airplay-" + token, packageName)
                        .setName(receiver.name)
                        .addSelectedRoute(routeId!!)
                        .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                        .setVolumeMax(100)
                        .setVolume(0)
                        .build()
                notifySessionCreated(requestId, session!!)
                notifySessionUpdated(session!!)
                Diagnostics.route(this, "session announced " + session!!.getId())
                changed()
            },
        )
        main.postDelayed(
            Runnable {
                if (destroyed || sequence != token || !creating) return@Runnable
                creating = false
                pending = MediaRoute2ProviderService.REQUEST_ID_NONE
                ++sequence
                if (BridgeState.capture != null) BridgeState.capture!!.disconnect()
                notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_NETWORK_ERROR)
            },
            25000,
        )
    }

    private fun prompt() {
        // Avoid background activity launches; the foreground sharing notification opens the app.
        BridgeState.status(R.string.route_not_ready)
    }

    override fun onReleaseSession(requestId: Long, sessionId: String) {
        Diagnostics.route(this, "release " + requestId + " " + sessionId)
        if (session == null || session!!.getId() != sessionId) {
            notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_INVALID_COMMAND)
            return
        }
        session = null
        if (BridgeState.capture != null) BridgeState.capture!!.disconnect()
        notifySessionReleased(sessionId)
    }

    override fun onSetRouteVolume(requestId: Long, routeId: String, volume: Int) {
        if (session == null || !session!!.getSelectedRoutes().contains(routeId)) {
            notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_INVALID_COMMAND)
            return
        }
        onSetSessionVolume(requestId, session!!.getId(), volume)
    }

    override fun onSetSessionVolume(requestId: Long, sessionId: String, volume: Int) {
        Log.i("HomePodBridgeVolume", "Route set=" + volume)
        if (session == null || session!!.getId() != sessionId) {
            notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_INVALID_COMMAND)
            return
        }
        val bounded = VolumePolicy.apply(volume)
        BridgeState.volume = bounded
        NativeSender.volume(bounded)
        session = android.media.RoutingSessionInfo.Builder(session!!).setVolume(bounded).build()
        notifySessionUpdated(session!!)
        changed()
    }

    override fun onSelectRoute(requestId: Long, sessionId: String, routeId: String) {
        notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_INVALID_COMMAND)
    }

    override fun onDeselectRoute(requestId: Long, sessionId: String, routeId: String) {
        notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_INVALID_COMMAND)
    }

    override fun onTransferToRoute(requestId: Long, sessionId: String, routeId: String) {
        notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_INVALID_COMMAND)
    }

    override fun onDestroy() {
        Diagnostics.route(this, "provider destroyed")
        destroyed = true
        if (HomePodRoutes.active == this) HomePodRoutes.active = null
        ++sequence
        main.removeCallbacksAndMessages(null)
        BridgeState.remove(this)
        if (discovery != null) discovery!!.close()
        super.onDestroy()
    }

    companion object {
        const val FEATURE: String = "dev.ryuya.homepodbridge.AIRPLAY"
        private var active: HomePodRoutes? = null

        @JvmStatic
        fun available(): Boolean {
            return HomePodRoutes.active != null && !HomePodRoutes.active!!.destroyed
        }

        @JvmStatic
        fun ensureDiscovery() {
            if (HomePodRoutes.available() && HomePodRoutes.active!!.discovery != null)
                HomePodRoutes.active!!.discovery!!.start()
        }

        @JvmStatic
        fun selectFromSystem(packageName: String?, routeId: String?) {
            if (HomePodRoutes.available())
                HomePodRoutes.active!!.onCreateSession(
                    MediaRoute2ProviderService.REQUEST_ID_NONE,
                    packageName!!,
                    routeId!!,
                    null,
                )
        }

        @JvmStatic
        fun restartCurrent(packageName: String?): Boolean {
            if (
                !HomePodRoutes.available() ||
                    HomePodRoutes.active!!.creating ||
                    HomePodRoutes.active!!.session == null ||
                    BridgeState.selected == null ||
                    BridgeState.capture == null
            )
                return false
            val provider = HomePodRoutes.active
            val receiver = BridgeState.selected
            val token: Long = ++provider!!.sequence
            provider!!.creating = true
            BridgeState.capture!!.select(
                receiver!!,
                packageName,
                CaptureService.ConnectionCallback { success: Boolean ->
                    if (provider!!.destroyed || provider!!.sequence != token)
                        return@ConnectionCallback
                    provider!!.creating = false
                    if (success && provider!!.session != null) {
                        provider!!.session =
                            android.media.RoutingSessionInfo.Builder(provider!!.session!!)
                                .setVolume(0)
                                .build()
                        provider!!.notifySessionUpdated(provider!!.session!!)
                    }
                    provider!!.changed()
                },
            )
            return true
        }

        @JvmStatic
        fun stopFromSystem() {
            if (!HomePodRoutes.available()) return
            val provider = HomePodRoutes.active
            ++provider!!.sequence
            provider!!.creating = false
            if (provider!!.session != null) {
                val id = provider!!.session!!.getId()
                provider!!.session = null
                provider!!.notifySessionReleased(id)
            }
            if (BridgeState.capture != null) BridgeState.capture!!.disconnect()
        }
    }
}
