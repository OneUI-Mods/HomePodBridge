package dev.ryuya.homepodbridge

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession.Token
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.concurrent.Executor
import java.util.function.Supplier
import kotlin.concurrent.Volatile
import kotlin.jvm.Synchronized
import kotlin.math.log
import kotlin.math.round

/* A lease on the unused speaker's media-volume commands, owned by one capture stream. */
class SystemVolumeEndpoint(context: Context, callback: IBinder) : AutoCloseable {
    private val audio: AudioManager
    private val manager: Any
    private val device: Any
    private val previousVolume: Any
    private val thread: HandlerThread = HandlerThread("HomePod-volume")
    private val handler: Handler
    private val callback: IBinder
    private val infoBuilder: Class<*>
    private val mediaSessions: MediaSessionManager
    private val remoteController: MediaController
    private val volumeKeys: VolumeKeyChanges
    private val volumeIcons: RemoteVolumeIcons = RemoteVolumeIcons()
    private val previousFineVolume: Int
    private var drainScheduled: Boolean = false
    private var iconRegistered: Boolean = false
    @Volatile private var panelErrorLogged: Boolean = false
    @Volatile private var closed: Boolean = false
    private var registered: Boolean = false

    init {
        this.callback = callback
        check(SystemVolumeUi.available) { "Remote volume panel not available" }
        audio = context.getSystemService<AudioManager?>(AudioManager::class.java)
        mediaSessions =
            context.getSystemService<MediaSessionManager?>(MediaSessionManager::class.java)
        remoteController =
            mediaSessions
                .getActiveSessions(null)
                .stream()
                .filter { session: MediaController? ->
                    RootProtocol.PACKAGE == session!!.getPackageName()
                }
                .filter { session: MediaController? ->
                    session!!.getPlaybackInfo() != null &&
                        session!!.getPlaybackInfo().getPlaybackType() == 2
                }
                .findFirst()
                .orElseThrow<IllegalStateException?>(
                    Supplier { IllegalStateException("Owned remote session unavailable") }
                )
        val loader = context.getClassLoader()
        val managerClass = XposedHelpers.findClass("android.media.AudioDeviceVolumeManager", loader)
        manager = XposedHelpers.newInstance(managerClass, context)
        device =
            XposedHelpers.newInstance(
                XposedHelpers.findClass("android.media.AudioDeviceAttributes", loader),
                2,
                2,
                "",
            )
        // Preserve other absolute-volume owners and nonstandard device behaviors.
        check(XposedHelpers.callMethod(manager, "getDeviceVolumeBehavior", device) as Int? == 0) {
            "Speaker already has a volume owner"
        }
        infoBuilder = XposedHelpers.findClass("android.media.VolumeInfo\$Builder", loader)
        val query =
            XposedHelpers.callMethod(
                XposedHelpers.newInstance(infoBuilder, AudioManager.STREAM_MUSIC),
                "build",
            )
        previousVolume = XposedHelpers.callMethod(manager, "getDeviceVolume", query, device)
        var fine: Int = -1
        var baseline: Int = XposedHelpers.callMethod(previousVolume, "getVolumeIndex") as Int
        val maximum: Int = XposedHelpers.callMethod(previousVolume, "getMaxVolumeIndex") as Int
        baseline = if (maximum > 0) baseline * 100 / maximum else 0
        try {
            fine =
                (XposedHelpers.callMethod(audio, "semGetFineVolume", AudioManager.STREAM_MUSIC, 2)
                    as Int?)!!
            baseline =
                Math.round(fine * 100f / (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 10))
        } catch (ignored: Throwable) {}
        previousFineVolume = fine
        volumeKeys = VolumeKeyChanges(baseline)
        thread.start()
        handler = Handler(thread.getLooper())
        try {
            val listenerClass =
                XposedHelpers.findClass(
                    "android.media.AudioDeviceVolumeManager\$OnAudioDeviceVolumeChangedListener",
                    loader,
                )
            val listener =
                Proxy.newProxyInstance(
                    loader,
                    arrayOf<Class<*>?>(listenerClass),
                    InvocationHandler { proxy: Any?, method: Method?, args: Array<Any?>? ->
                        try {
                            if (method!!.getName() == "hashCode")
                                return@InvocationHandler System.identityHashCode(proxy)
                            if (method!!.getName() == "equals")
                                return@InvocationHandler proxy === args!![0]
                            if (method!!.getName() == "toString")
                                return@InvocationHandler "HomePod volume lease"
                            if (closed || args == null || args.size < 2)
                                return@InvocationHandler null
                            val stream: Int =
                                XposedHelpers.callMethod(args!![1], "getStreamType") as Int
                            val media: Boolean =
                                stream == AudioManager.STREAM_MUSIC &&
                                    audio.getMode() == AudioManager.MODE_NORMAL
                            if (method!!.getName() == "onAudioDeviceVolumeAdjusted") {
                                val direction: Int = args!![2] as Int
                                val mode: Int = args!![3] as Int
                                if (mode == 2 || direction == AudioManager.ADJUST_SAME)
                                    return@InvocationHandler null
                                if (media) send(RootProtocol.ADJUST_VOLUME, direction)
                                else {
                                    // Bypass our absolute-volume callback for normal non-media/call
                                    // controls.
                                    val bypass =
                                        XposedHelpers.getStaticIntField(
                                            AudioManager::class.java,
                                            "FLAG_ABSOLUTE_VOLUME",
                                        )
                                    audio.adjustStreamVolume(
                                        stream,
                                        direction,
                                        bypass or AudioManager.FLAG_SHOW_UI,
                                    )
                                }
                            } else if (
                                method!!.getName() == "onAudioDeviceVolumeChanged" && media
                            ) {
                                volumeKeys.changed(
                                    XposedHelpers.callMethod(args!![1], "getVolumeIndex") as Int,
                                    SystemClock.uptimeMillis(),
                                )
                                restoreLocalVolume()
                                scheduleKeys()
                            }
                            return@InvocationHandler null
                        } catch (error: Throwable) {
                            close()
                            return@InvocationHandler null
                        }
                    },
                )
            val volumes = ArrayList<Any?>()
            val streams: Int =
                XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.media.AudioSystem", loader),
                    "getNumStreamTypes",
                ) as Int
            for (stream in 0 until streams) {
                if (
                    !(XposedHelpers.callStaticMethod(
                        AudioManager::class.java,
                        "isVolumeControlStreamType",
                        stream,
                    ) as Boolean)
                )
                    continue
                val builder = XposedHelpers.newInstance(infoBuilder, stream)
                XposedHelpers.callMethod(
                    builder,
                    "setMinVolumeIndex",
                    if (stream == AudioManager.STREAM_MUSIC) 0
                    else audio.getStreamMinVolume(stream),
                )
                XposedHelpers.callMethod(
                    builder,
                    "setMaxVolumeIndex",
                    if (stream == AudioManager.STREAM_MUSIC) 100
                    else audio.getStreamMaxVolume(stream),
                )
                // Every index stays unset: registration must not alter local volumes.
                volumes.add(XposedHelpers.callMethod(builder, "build"))
            }
            val executor = Executor { command: Runnable? -> handler.post(command!!) }
            registered = true
            XposedHelpers.callMethod(
                manager,
                "setDeviceAbsoluteMultiVolumeBehavior",
                device,
                volumes,
                true,
                executor,
                listener,
            )
            SystemVolumeUi.active = this
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun ownsSession(sessionId: Any?): Boolean {
        if (closed) return false
        try {
            var token: Any? = sessionId
            if (token !is Token) token = XposedHelpers.getObjectField(sessionId, "token")
            return remoteController.getSessionToken() == token
        } catch (ignored: Throwable) {
            return false
        }
    }

    fun rememberRemoteStream(callbacks: Any?, sessionId: Any?) {
        if (!ownsSession(sessionId)) return
        try {
            val streams = XposedHelpers.getObjectField(callbacks, "mRemoteStreams")
            synchronized(streams) {
                run {
                    val patternPrefix0 = streams is MutableMap<*, *>
                    val stream = if (patternPrefix0) streams.get(sessionId) as? Int else null
                    if (patternPrefix0 && stream != null) {
                        volumeIcons.stream = stream
                        if (!iconRegistered) {
                            iconRegistered = true
                            Log.i("HomePodBridgeUi", "Owned volume panel registered")
                        }
                    }
                }
            }
        } catch (ignored: Throwable) {}
    }

    fun handleIconAction(action: Any?, state: Any?): Any? {
        if (closed || state == null) return null
        try {
            val type = XposedHelpers.callMethod(action, "getActionType")
            if (type !is Enum<*> || type.name != "ACTION_VOLUME_ICON_CLICKED") return null
            val stream: Int = XposedHelpers.callMethod(action, "getStream") as Int
            if (!volumeIcons.owns(stream)) return null
            val rows: MutableList<*>? =
                XposedHelpers.callMethod(state, "getVolumeRowList") as MutableList<*>?
            for (row in rows!!) {
                if (XposedHelpers.callMethod(row, "getStreamType") as Int? != stream) continue
                val target =
                    volumeIcons.target(stream, XposedHelpers.callMethod(row, "getLevel") as Int)
                if (target == null) return null
                val builder =
                    XposedHelpers.newInstance(
                        XposedHelpers.findClass(
                            state.javaClass.getName() + "\$Builder",
                            state.javaClass.getClassLoader(),
                        ),
                        state,
                    )
                val noDispatch =
                    XposedHelpers.getStaticObjectField(
                        XposedHelpers.findClass(
                            state.javaClass.getName() + "\$StateType",
                            state.javaClass.getClassLoader(),
                        ),
                        "STATE_NO_DISPATCH",
                    )
                XposedHelpers.callMethod(builder, "setStateType", noDispatch)
                val result = XposedHelpers.callMethod(builder, "build")
                handler.post(Runnable { send(RootProtocol.SET_VOLUME, target) })
                Log.i("HomePodBridgeUi", "Owned volume icon target=" + target)
                return result
            }
        } catch (error: Throwable) {
            Log.w(
                "HomePodBridgeUi",
                "Owned icon action unavailable: " + error.javaClass.getSimpleName(),
            )
        }
        return null
    }

    fun enableIconState(state: Any?): Any? {
        if (closed || state == null) return state
        try {
            val rows = XposedHelpers.callMethod(state, "getVolumeRowList")
            val enabled = enableOwnedIcon(rows)
            if (enabled === rows) return state
            val builder =
                XposedHelpers.newInstance(
                    XposedHelpers.findClass(
                        state.javaClass.getName() + "\$Builder",
                        state.javaClass.getClassLoader(),
                    ),
                    state,
                )
            XposedHelpers.callMethod(builder, "setVolumeRowList", enabled)
            return XposedHelpers.callMethod(builder, "build")
        } catch (ignored: Throwable) {
            return state
        }
    }

    fun enableOwnedIcon(rows: Any?): Any? {
        if (closed || rows !is MutableList<*>) return rows
        try {
            val updated = ArrayList<Any?>(rows)
            for (i in updated.indices) {
                val row = updated.get(i)
                val stream: Int = XposedHelpers.callMethod(row, "getStreamType") as Int
                if (!volumeIcons.owns(stream)) continue
                val builder =
                    XposedHelpers.findClass(
                        row!!.javaClass.getName() + "\$Builder",
                        row!!.javaClass.getClassLoader(),
                    )
                val copy = XposedHelpers.newInstance(builder, row)
                XposedHelpers.callMethod(copy, "isIconClickable", true)
                updated.set(i, XposedHelpers.callMethod(copy, "build"))
            }
            return updated
        } catch (ignored: Throwable) {
            return rows
        }
    }

    fun receiverNameFor(sessionId: Any?): String? {
        if (!ownsSession(sessionId)) return null
        try {
            val metadata = remoteController.getMetadata()
            return if (metadata == null) null
            else metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        } catch (ignored: Throwable) {
            return null
        }
    }

    fun redirectLocalUi(controller: Any, flags: Int): Boolean {
        if (closed || audio.getMode() != AudioManager.MODE_NORMAL) return false
        try {
            val sessions = XposedHelpers.getObjectField(controller, "mMediaSessions")
            val token = remoteController.getSessionToken()
            val records = XposedHelpers.getObjectField(sessions, "mRecords")
            if (records is MutableMap<*, *> && !records.containsKey(token)) {
                try {
                    XposedHelpers.callStaticMethod(
                        sessions.javaClass,
                        "access\$onActiveSessionsUpdatedH",
                        sessions,
                        mediaSessions.getActiveSessions(null),
                    )
                } catch (ignored: Throwable) {
                    XposedHelpers.callMethod(
                        sessions,
                        "onActiveSessionsUpdatedH",
                        mediaSessions.getActiveSessions(null),
                    )
                }
                if (!records.containsKey(token)) return false
            }
            val metadata = remoteController.getMetadata()
            var name: String? =
                if (metadata == null) null
                else metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            if (name == null) name = "HomePod Bridge"
            XposedHelpers.callMethod(
                sessions,
                "updateRemoteH",
                token,
                name,
                remoteController.getPlaybackInfo(),
            )
            val callbacks = XposedHelpers.getObjectField(controller, "mMediaSessionsCallbacksW")
            val idClass =
                XposedHelpers.findClassIfExists(
                    "com.android.settingslib.volume.MediaSessions\$SessionId\$Media",
                    controller.javaClass.getClassLoader(),
                )
            val id: Any? = if (idClass == null) token else XposedHelpers.newInstance(idClass, token)
            XposedHelpers.callMethod(callbacks, "onRemoteVolumeChanged", id, flags)
            val fromKey = XposedHelpers.getStaticIntField(AudioManager::class.java, "FLAG_FROM_KEY")
            if (
                (flags and (fromKey or AudioManager.FLAG_SHOW_UI)) ==
                    (fromKey or AudioManager.FLAG_SHOW_UI)
            ) {
                val now = SystemClock.uptimeMillis()
                handler.post(
                    Runnable {
                        if (!closed) {
                            volumeKeys.key(now)
                            scheduleKeys()
                        }
                    }
                )
            }
            return true
        } catch (error: Throwable) {
            if (!panelErrorLogged) {
                panelErrorLogged = true
                XposedBridge.log("HomePod Bridge: remote volume panel failed")
                XposedBridge.log(error)
            }
            return false // Preserve the ROM's panel when its internals differ.
        }
    }

    private fun restoreLocalVolume() {
        if (previousFineVolume >= 0) {
            val now: Int =
                XposedHelpers.callMethod(audio, "semGetFineVolume", AudioManager.STREAM_MUSIC, 2)
                    as Int
            if (now == previousFineVolume) return
            val bypass =
                XposedHelpers.getStaticIntField(AudioManager::class.java, "FLAG_ABSOLUTE_VOLUME")
            XposedHelpers.callMethod(
                audio,
                "semSetFineVolume",
                AudioManager.STREAM_MUSIC,
                previousFineVolume,
                bypass,
                2,
            )
        } else {
            val now = XposedHelpers.callMethod(manager, "getDeviceVolume", previousVolume, device)
            if (
                XposedHelpers.callMethod(now, "getVolumeIndex") ==
                    XposedHelpers.callMethod(previousVolume, "getVolumeIndex")
            )
                return
            XposedHelpers.callMethod(manager, "notifyAbsoluteVolumeChanged", previousVolume, device)
        }
    }

    private fun scheduleKeys() {
        if (closed || drainScheduled) return
        drainScheduled = true
        handler.postDelayed(
            Runnable {
                drainScheduled = false
                if (closed) return@Runnable
                for (direction in volumeKeys.drain(SystemClock.uptimeMillis())) send(
                    RootProtocol.ADJUST_VOLUME,
                    direction!!,
                )
                if (volumeKeys.pendingKeys()) scheduleKeys()
            },
            30,
        )
    }

    private fun send(code: Int, value: Int) {
        Log.i(
            "HomePodBridgeUi",
            "Volume callback code=" + code + " value=" + value + " closed=" + closed,
        )
        if (closed) return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(RootProtocol.VOLUME_CALLBACK)
            data.writeInt(value)
            callback.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: RemoteException) {
            close()
        } finally {
            data.recycle()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (SystemVolumeUi.active == this) SystemVolumeUi.active = null
        if (registered) {
            try {
                if (
                    XposedHelpers.callMethod(manager, "getDeviceVolumeBehavior", device) as Int? ==
                        3
                ) {
                    XposedHelpers.callMethod(manager, "resetDeviceAbsoluteVolumeBehavior", device)
                    if (previousFineVolume >= 0)
                        XposedHelpers.callMethod(
                            audio,
                            "semSetFineVolume",
                            AudioManager.STREAM_MUSIC,
                            previousFineVolume,
                            0,
                            2,
                        )
                    else
                        XposedHelpers.callMethod(manager, "setDeviceVolume", previousVolume, device)
                }
            } catch (ignored: Throwable) {}
        }
        thread.quitSafely()
    }
}
