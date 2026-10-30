package dev.ryuya.homepodbridge

import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.MediaSession.Callback
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Handler
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log

/* A remote volume endpoint owned only while whole-device sharing is connected. */
class WholeDeviceVolume(context: Context, main: Handler) : BridgeState.Observer, AutoCloseable {
    private val context: Context
    private val main: Handler
    private var session: MediaSession? = null
    private var provider: VolumeProvider? = null
    private var receiverName: String? = null
    private var lastAudible: Int = 1
    private val iconEcho: VolumeEchoGuard = VolumeEchoGuard()
    private val callback: Binder =
        object : Binder() {
            @Throws(RemoteException::class)
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != RootProtocol.ADJUST_VOLUME && code != RootProtocol.SET_VOLUME)
                    return super.onTransact(code, data, reply, flags)
                data.enforceInterface(RootProtocol.VOLUME_CALLBACK)
                try {
                    if (
                        Binder.getCallingUid() !=
                            context
                                .getPackageManager()
                                .getApplicationInfo("com.android.systemui", 0)
                                .uid
                    )
                        throw SecurityException("SystemUI only")
                } catch (e: NameNotFoundException) {
                    return false
                }
                val value = data.readInt()
                Log.i(
                    "HomePodBridgeVolume",
                    "System request code=" + code + " value=" + value,
                )
                main.post(
                    Runnable {
                        if (code == RootProtocol.ADJUST_VOLUME) {
                            if (session != null && BridgeState.connected)
                                session!!
                                    .getController()
                                    .adjustVolume(value, AudioManager.FLAG_SHOW_UI)
                        } else {
                            iconEcho.explicitChange(
                                BridgeState.volume,
                                VolumePolicy.apply(value),
                                SystemClock.uptimeMillis(),
                            )
                            apply(value)
                        }
                    }
                )
                return true
            }
        }

    init {
        this.context = context
        this.main = main
        BridgeState.observe(this)
    }

    override fun changed() {
        if (
            (BridgeState.capture != null && BridgeState.capture!!.diagnosticAudio()) ||
                !BridgeState.connected ||
                BridgeState.selected == null ||
                !StreamSettings.load(context)!!.wholeDevice
        ) {
            releaseSession()
            return
        }
        if (session == null) {
            provider =
                object :
                    VolumeProvider(
                        VolumeProvider.VOLUME_CONTROL_ABSOLUTE,
                        100,
                        BridgeState.volume,
                    ) {
                    override fun onAdjustVolume(direction: Int) {
                        main.post(Runnable { adjust(direction) })
                    }

                    override fun onSetVolumeTo(volume: Int) {
                        Log.i("HomePodBridgeVolume", "Panel set=" + volume)
                        main.post(
                            Runnable {
                                if (
                                    iconEcho.ignore(
                                        VolumePolicy.apply(volume),
                                        SystemClock.uptimeMillis(),
                                    )
                                ) {
                                    if (provider != null)
                                        provider!!.setCurrentVolume(BridgeState.volume)
                                    Log.i("HomePodBridgeVolume", "Ignored stale icon echo")
                                } else apply(volume)
                            }
                        )
                    }
                }
            session = MediaSession(context, "HomePod Bridge volume")
            session!!.setCallback(object : Callback() {}, main)
            session!!.setPlaybackToRemote(provider!!)
            session!!.setPlaybackState(
                android.media.session.PlaybackState.Builder()
                    .setState(
                        PlaybackState.STATE_PLAYING,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1f,
                    )
                    .build()
            )
            session!!.setActive(true)
            if (BridgeState.capture != null)
                RootConnector.volumeEndpoint(BridgeState.capture!!.lifetime(), callback)
        }
        if (!BridgeState.selected!!.name.equals(receiverName)) {
            receiverName = BridgeState.selected!!.name
            session!!.setMetadata(
                android.media.MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, receiverName)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, receiverName)
                    .putString(
                        MediaMetadata.METADATA_KEY_ARTIST,
                        context.getString(R.string.app_name),
                    )
                    .build()
            )
        }
        if (BridgeState.volume > 0) lastAudible = BridgeState.volume
        if (provider!!.getCurrentVolume() != BridgeState.volume)
            provider!!.setCurrentVolume(BridgeState.volume)
    }

    private fun adjust(direction: Int) {
        val target: Int =
            when (direction) {
                AudioManager.ADJUST_RAISE -> BridgeState.volume + 1
                AudioManager.ADJUST_LOWER -> BridgeState.volume - 1
                AudioManager.ADJUST_MUTE -> 0
                AudioManager.ADJUST_UNMUTE -> lastAudible
                AudioManager.ADJUST_TOGGLE_MUTE -> if (BridgeState.volume == 0) lastAudible else 0
                else -> BridgeState.volume
            }
        apply(target)
    }

    private fun apply(requested: Int) {
        if (
            session == null || !BridgeState.connected || !StreamSettings.load(context)!!.wholeDevice
        )
            return
        val bounded = VolumePolicy.apply(requested)
        Log.i("HomePodBridgeVolume", "Apply=" + bounded)
        if (bounded == BridgeState.volume) return
        BridgeState.volume = bounded
        NativeSender.volume(bounded)
        BridgeState.publish()
    }

    private fun releaseSession() {
        iconEcho.clear()
        if (session == null) return
        session!!.setActive(false)
        session!!.release()
        session = null
        provider = null
        receiverName = null
    }

    override fun close() {
        BridgeState.remove(this)
        releaseSession()
    }
}
