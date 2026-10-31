package dev.ryuya.homepodbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjection.Callback
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.PowerManager
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import java.io.FileDescriptor
import java.io.IOException
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.LongSupplier
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.math.max

class CaptureService : Service() {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val generation: AtomicInteger = AtomicInteger()
    private var projection: MediaProjection? = null
    private var musicUid: Int = 0
    private var systemMode: Boolean = false
    private var lifetime: IBinder = Binder()
    @Volatile private var systemPipe: ParcelFileDescriptor? = null
    @Volatile private var activeDevicePath: OwnedAudioPath? = null
    @Volatile private var deviceBufferSuppressed: Boolean = false
    private var destroyed: Boolean = false
    @Volatile private var zeroProbeActive: Boolean = false
    @Volatile private var zeroProbeFrames: Long = 0
    @Volatile private var dedicatedHalProbe: Boolean = false
    @Volatile private var activeZeroHal: RootHalInput? = null
    @Volatile private var halPendingFrames: Long = -1
    @Volatile private var halNonce: String? = null
    private var pendingCallback: CaptureService.ConnectionCallback? = null
    private var callbackGeneration: Int = 0
    private var selectedPackage: String? = BridgeState.MUSIC
    private var wholeDeviceVolume: WholeDeviceVolume? = null

    fun diagnosticAudio(): Boolean {
        return zeroProbeActive
    }

    fun lifetime(): IBinder {
        return lifetime
    }

    override fun onCreate() {
        super.onCreate()
        wholeDeviceVolume = WholeDeviceVolume(this, main)
    }

    fun reconfigure() {
        if (BridgeState.selected == null) return
        if (HomePodRoutes.restartCurrent(selectedPackage)) return
        val receiver = BridgeState.selected
        if (HomePodRoutes.available())
            HomePodRoutes.selectFromSystem(selectedPackage, receiver!!.id)
        else
            select(
                receiver!!,
                selectedPackage,
                CaptureService.ConnectionCallback { success: Boolean -> },
            )
    }

    private val projectionCallback: Callback =
        object : Callback() {
            override fun onStop() {
                stopSelf()
            }
        }

    fun interface ConnectionCallback {
        fun finished(success: Boolean)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || CaptureService.STOP == intent.getAction()) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        if (projection != null || systemMode) return Service.START_NOT_STICKY
        if (intent.getBooleanExtra("system", false) && RootConnector.available()) {
            startForeground(
                1,
                notification(getString(R.string.sharing))!!,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            systemMode = true
            BridgeState.capture = this
            BridgeState.status = R.string.sharing
            BridgeState.publish()
            return Service.START_NOT_STICKY
        }
        val consent = intent.getParcelableExtra<Intent?>("consent", Intent::class.java)
        if (consent == null) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        try {
            startForeground(
                1,
                notification(getString(R.string.sharing))!!,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            if (!StreamSettings.load(this)!!.wholeDevice)
                musicUid = getPackageManager().getApplicationInfo(BridgeState.MUSIC, 0).uid
            projection =
                getSystemService<MediaProjectionManager?>(MediaProjectionManager::class.java)
                    .getMediaProjection(intent.getIntExtra("result", 0), consent)
            checkNotNull(projection) { "Projection not available" }
            projection!!.registerCallback(projectionCallback, main)
            BridgeState.capture = this
            BridgeState.status = R.string.sharing
            BridgeState.publish()
        } catch (e: Exception) {
            BridgeState.status = R.string.capture_failed
            stopSelf()
        }
        return Service.START_NOT_STICKY
    }

    fun select(receiver: Receiver, callback: CaptureService.ConnectionCallback) {
        select(receiver, BridgeState.MUSIC, callback)
    }

    fun select(
        receiver: Receiver,
        sourcePackage: String?,
        callback: CaptureService.ConnectionCallback,
    ) {
        select(receiver, sourcePackage, callback, false)
    }

    fun selectZeroProbe(receiver: Receiver, callback: CaptureService.ConnectionCallback) {
        select(receiver, BridgeState.MUSIC, callback, true)
    }

    @JvmOverloads
    fun selectZeroHalProbe(
        receiver: Receiver,
        callback: CaptureService.ConnectionCallback,
        whole: Boolean = StreamSettings.load(this)!!.wholeDevice,
        pulse: Boolean = false,
    ) {
        select(receiver, BridgeState.MUSIC, callback, true, true, whole, pulse)
    }

    private fun select(
        receiver: Receiver,
        sourcePackage: String?,
        callback: CaptureService.ConnectionCallback,
        zeroProbe: Boolean,
        dedicatedHal: Boolean = false,
        testWhole: Boolean = false,
        pulse: Boolean = false,
    ) {
        if (dedicatedHal && (!zeroProbe || !systemMode)) {
            callback.finished(false)
            return
        }
        if (destroyed || (projection == null && !systemMode)) {
            callback.finished(false)
            return
        }
        if (
            !systemMode &&
                !StreamSettings.load(this)!!.wholeDevice &&
                (BridgeState.MUSIC != sourcePackage)
        ) {
            callback.finished(false)
            return
        }
        cancelPending()
        selectedPackage = sourcePackage
        val token = generation.incrementAndGet()
        closeSystemInput()
        lifetime = Binder()
        pendingCallback = callback
        callbackGeneration = token
        BridgeState.selected = receiver
        BridgeState.connected = false
        BridgeState.volume = 0
        BridgeState.timing = null
        BridgeState.receiverRejection = ReceiverRejection.NONE
        ReceiverRejection.NONE.save(this)
        BridgeState.pipeline = null
        BridgeState.networkTiming = null
        val connection = ConnectionTiming()
        BridgeState.connectionTiming = connection
        BridgeState.activePhoneTiming = null
        BridgeState.phonePath = null
        zeroProbeActive = zeroProbe
        zeroProbeFrames = 0
        dedicatedHalProbe = dedicatedHal
        halPendingFrames = -1
        halNonce = null
        BridgeState.status = R.string.connecting
        BridgeState.publish()
        val connectionLifetime: IBinder? = lifetime
        CaptureService.worker!!.execute(
            Runnable {
                stream(
                    receiver,
                    sourcePackage,
                    token,
                    connectionLifetime,
                    zeroProbe,
                    dedicatedHal,
                    testWhole,
                    pulse,
                    connection,
                )
            }
        )
    }

    private fun stream(
        receiver: Receiver,
        sourcePackage: String?,
        token: Int,
        connectionLifetime: IBinder?,
        zeroProbe: Boolean,
        forceDedicated: Boolean,
        testWhole: Boolean,
        pulse: Boolean,
        connection: ConnectionTiming,
    ) {
        connection.mark(ConnectionTiming.Stage.WORKER)
        var recorder: AudioRecord? = null
        var zeroHal: RootHalInput? = null
        val connectivity = getSystemService<ConnectivityManager?>(ConnectivityManager::class.java)
        val previousNetwork = connectivity.getBoundNetworkForProcess()
        var networkBound: Boolean = false
        var localRoute: LocalRoute? = null
        var networkLease: NetworkLease? = null
        var devicePath: OwnedAudioPath? = null
        var started: Boolean = false
        var capturedFrames: Long = 0
        var signalBlocks: Long = 0
        val savedSettings = StreamSettings.load(this)
        val networkSettings = NetworkSettings.load(this)
        val settings: StreamSettings? =
            if (forceDedicated) savedSettings!!.withWholeDevice(testWhole)
            else if (zeroProbe) savedSettings!!.withWholeDevice(true) else savedSettings
        var dedicatedHal: Boolean =
            systemMode && (forceDedicated || (!zeroProbe && settings!!.effectiveDedicatedCapture()))
        setBackend(
            token,
            if (dedicatedHal) BridgeState.CaptureBackend.CHECKING
            else BridgeState.CaptureBackend.STANDARD,
        )
        val wake =
            getSystemService<PowerManager?>(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomePodBridge:stream")
        wake.setReferenceCounted(false)
        var failure = R.string.connection_failed
        try {
            if (generation.get() != token) return
            if (CaptureService.halCleanupUnconfirmed)
                throw IOException("Previous capture cleanup unconfirmed")
            wake.acquire(60000)
            var nextWakeRefresh: Long = SystemClock.elapsedRealtime() + 30000
            if (
                !systemMode &&
                    (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED)
            ) {
                throw SecurityException("Audio permission revoked")
            }
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (ignored: RuntimeException) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            }
            for (network in connectivity.getAllNetworks()) {
                val capabilities = connectivity.getNetworkCapabilities(network)
                if (
                    capabilities != null &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                ) {
                    networkBound = connectivity.bindProcessToNetwork(network)
                    if (networkBound) break
                }
            }
            if (!networkBound) localRoute = LocalRoute.acquire(receiver.host!!)
            networkLease = NetworkLease(this, networkSettings!!.priority)
            connection.mark(ConnectionTiming.Stage.NETWORK)
            var rootInput: PcmPipe? = null
            if (dedicatedHal && settings!!.wholeDevice) {
                try {
                    if (!RootConnector.openExternal(connectionLifetime, true)) dedicatedHal = false
                } catch (unavailable: RemoteException) {
                    RootConnector.close(connectionLifetime)
                    dedicatedHal = false
                } catch (unavailable: RuntimeException) {
                    RootConnector.close(connectionLifetime)
                    dedicatedHal = false
                }
                if (!dedicatedHal) setBackend(token, BridgeState.CaptureBackend.FALLBACK)
            }
            val startingPacketFrames = settings!!.packetFramesForBackend(dedicatedHal)
            connection.mark(ConnectionTiming.Stage.SENDER)
            startSender(receiver, settings!!, dedicatedHal, networkSettings!!)
            if (dedicatedHal) {
                try {
                    zeroHal =
                        RootHalInput(
                            this,
                            sourcePackage!!,
                            settings.wholeDevice,
                            zeroProbe,
                            pulse,
                            if (settings!!.effectiveShortMixerPeriod())
                                settings!!.effectivePhoneTiming()
                            else null,
                        )
                    activeZeroHal = zeroHal
                    halNonce = zeroHal.nonce
                    rootInput =
                        PcmPipe(
                            Channels.newChannel(zeroHal.input()),
                            true,
                            LongSupplier { System.nanoTime() },
                            PcmPipe.HAL_MAGIC,
                        )
                    BridgeState.captureRegistration = zeroHal.registration
                    main.post(
                        Runnable {
                            if (!destroyed && generation.get() == token)
                                BridgeState.activePhoneTiming =
                                    if (settings!!.effectiveShortMixerPeriod())
                                        settings!!.effectivePhoneTiming()
                                    else null
                        }
                    )
                    setBackend(
                        token,
                        if (zeroProbe) BridgeState.CaptureBackend.DEDICATED_TEST
                        else if (settings!!.effectiveShortMixerPeriod())
                            BridgeState.CaptureBackend.DEDICATED_SHORT
                        else BridgeState.CaptureBackend.DEDICATED,
                    )
                    if (generation.get() != token) return
                } catch (unavailable: RootHalInput.StartupException) {
                    if (!unavailable.safeFallback) {
                        CaptureService.halCleanupUnconfirmed = true
                        throw unavailable
                    }
                    RootConnector.close(connectionLifetime)
                    dedicatedHal = false
                    setBackend(token, BridgeState.CaptureBackend.FALLBACK)
                }
            }
            if (generation.get() != token) return
            if (systemMode && !dedicatedHal) {
                val opened =
                    RootConnector.open(sourcePackage, connectionLifetime, settings!!, zeroProbe)
                val pipe = opened.pipe
                systemPipe = pipe
                BridgeState.captureRegistration = opened.registration
                if (generation.get() != token) {
                    closeSystemInput()
                    return
                }
                val input = AutoCloseInputStream(pipe)
                rootInput = PcmPipe(input.getChannel(), opened.timed)
                if (
                    settings!!.effectiveDeviceBuffer() &&
                        !deviceBufferSuppressed &&
                        opened.registration != null
                ) {
                    val app = getApplicationContext()
                    devicePath =
                        OwnedAudioPath(
                            OwnedAudioPath.Backend { operation: Int ->
                                HalInspection.operateRoute(app, opened.registration, operation)
                            },
                            CaptureService.devicePaths!!,
                            Consumer { update: OwnedAudioPath.Update? ->
                                main.post(
                                    Runnable {
                                        if (destroyed || generation.get() != token) return@Runnable
                                        BridgeState.phonePath = update
                                        BridgeState.publishTiming()
                                        if (
                                            update!!.state ===
                                                OwnedAudioPath.State.RECOVERY_REQUIRED
                                        ) {
                                            deviceBufferSuppressed = true
                                            reconfigure()
                                        }
                                    }
                                )
                            },
                            settings.deviceBufferFrames,
                        )
                    activeDevicePath = devicePath
                    devicePath.start()
                } else if (settings!!.effectiveDeviceBuffer()) {
                    main.post(
                        Runnable {
                            if (!destroyed && generation.get() == token) {
                                BridgeState.phonePath =
                                    OwnedAudioPath.Update(
                                        OwnedAudioPath.State.STANDARD,
                                        "Optional device buffering unavailable; existing capture retained",
                                    )
                                BridgeState.publishTiming()
                            }
                        }
                    )
                }
            } else if (!systemMode) {
                val captureBuilder =
                    android.media.AudioPlaybackCaptureConfiguration.Builder(projection!!)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                if (settings.wholeDevice)
                    captureBuilder
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                else captureBuilder.addMatchingUid(musicUid)
                val capture = captureBuilder.build()
                val format =
                    android.media.AudioFormat.Builder()
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                val minimum =
                    AudioRecord.getMinBufferSize(
                        44100,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                recorder =
                    android.media.AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setAudioPlaybackCaptureConfig(capture)
                        .setBufferSizeInBytes(max(settings!!.samplesPerRead() * 2, minimum))
                        .build()
                check(recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                    "Recorder unavailable"
                }
                recorder.startRecording()
                check(recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    "Capture unavailable"
                }
            }
            connection.mark(ConnectionTiming.Stage.CAPTURE)
            if (startingPacketFrames != settings!!.packetFramesForBackend(dedicatedHal))
                startSender(receiver, settings!!, dedicatedHal, networkSettings!!)
            NativeSender.inputReady()
            val pcm =
                ByteBuffer.allocateDirect(settings!!.samplesPerRead() * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
            val deadline: Long = System.nanoTime() + 20000000000L
            var nextTiming: Long = 0
            while (generation.get() == token) {
                val now = SystemClock.elapsedRealtime()
                if (now >= nextWakeRefresh) {
                    wake.acquire(60000)
                    nextWakeRefresh = now + 30000
                }
                val state = NativeSender.state()
                if (state < 0 || (!started && System.nanoTime() > deadline)) break
                if (state == 2 && now >= nextTiming) {
                    val timing = NativeSender.timing()
                    val response = ReceiverRejection.fromNative(NativeSender.receiverRejection())
                    val networkTiming =
                        NetworkTiming(NativeSender.network(), networkLease.status(this))
                    if (timing != null && timing.size >= 4 && timing[1] > 0) {
                        val pipeline =
                            PipelineTiming(
                                if (systemMode && !dedicatedHal)
                                    RootConnector.captureTiming(connectionLifetime)
                                else null,
                                NativeSender.pipeline(),
                                if (rootInput == null) -1 else rootInput.transitMicros(),
                                System.nanoTime(),
                                if (dedicatedHal) rootInput!!.pendingFrames() else -1,
                            )
                        main.post(
                            Runnable {
                                if (generation.get() != token || destroyed) return@Runnable
                                BridgeState.timing = timing
                                BridgeState.receiverRejection = response
                                BridgeState.pipeline = pipeline
                                BridgeState.networkTiming = networkTiming
                                BridgeState.publishTiming()
                            }
                        )
                    }
                    nextTiming = now + 2000
                }
                if (!started && state == 2) {
                    val authenticatedAt = NativeSender.preparedAtNanos()
                    connection.markAt(
                        ConnectionTiming.Stage.AUTHENTICATED,
                        if (authenticatedAt > 0) authenticatedAt else System.nanoTime(),
                    )
                    connection.mark(ConnectionTiming.Stage.READY)
                    started = true
                    main.post(
                        Runnable {
                            if (generation.get() != token || destroyed) return@Runnable
                            BridgeState.connected = true
                            deleteFile("last-capture-error.txt")
                            BridgeState.status = R.string.playing
                            BridgeState.publish()
                            getSystemService<NotificationManager?>(NotificationManager::class.java)
                                .notify(
                                    1,
                                    notification(getString(R.string.playing, receiver.name)),
                                )
                            complete(token, true)
                        }
                    )
                }
                val read: Int =
                    if (rootInput != null) rootInput.read(pcm)
                    else recorder!!.read(pcm, pcm.capacity(), AudioRecord.READ_NON_BLOCKING)
                if (dedicatedHal) halPendingFrames = rootInput!!.pendingFrames()
                if (read < 0) {
                    failure = R.string.capture_failed
                    break
                }
                if (read > 0 && state == 2) {
                    if (read % 4 != 0) throw IOException("Truncated stereo frame")
                    capturedFrames += (read / 4).toLong()
                    var i: Int = 0
                    while (i < read) {
                        if (pcm.getShort(i).toInt() != 0) {
                            signalBlocks++
                            break
                        }
                        i += 2
                    }
                    val acoustic: AcousticProbe? =
                        if (zeroProbe) AcousticProbe.forLifetime(connectionLifetime) else null
                    if (zeroProbe) {
                        ZeroProbePcm.clear(pcm, read)
                        if (acoustic != null) acoustic.render(pcm, read)
                        zeroProbeFrames += (read / 4).toLong()
                    }
                    while (
                        !NativeSender.write(pcm, read) &&
                            generation.get() == token &&
                            NativeSender.state() == 2
                    ) Thread.sleep(2)
                    if (acoustic != null && generation.get() == token && NativeSender.state() == 2)
                        acoustic.accepted()
                }
                if (read == 0) Thread.sleep((if (settings.lowLatency) 1 else 5).toLong())
            }
        } catch (e: Exception) {
            if (!destroyed && generation.get() == token) {
                try {
                    openFileOutput("last-capture-error.txt", Context.MODE_PRIVATE).use { error ->
                        error.write(
                            (e.javaClass.getSimpleName() + ": " + e.message).toByteArray(
                                StandardCharsets.UTF_8
                            )
                        )
                    }
                } catch (ignored: IOException) {}
            }
            failure = R.string.capture_failed
        } finally {
            if (zeroHal != null) {
                zeroHal.close()
                if (activeZeroHal == zeroHal) activeZeroHal = null
            }
            try {
                try {
                    openFileOutput("last-connection.txt", Context.MODE_PRIVATE).use { diagnostics ->
                        diagnostics.write(
                            NativeSender.diagnostic()!!.toByteArray(StandardCharsets.UTF_8)
                        )
                        diagnostics.write(
                            (("\ncapturedFrames=" +
                                    capturedFrames +
                                    " signalBlocks=" +
                                    signalBlocks +
                                    " wholeDevice=" +
                                    settings!!.wholeDevice +
                                    " bufferMs=" +
                                    settings!!.effectiveBufferMs() +
                                    " blockMs=" +
                                    settings!!.effectiveBlockMs() +
                                    " latencyMs=" +
                                    settings!!.requestedPresentationMs() +
                                    " receiverMinimumMs=" +
                                    settings!!.requestedReceiverMinimumMs() +
                                    " receiverMaximumMs=" +
                                    settings!!.requestedReceiverMaximumMs() +
                                    " independentTiming=" +
                                    settings.receiverTiming!!.independent +
                                    " profile=" +
                                    settings!!.profile() +
                                    " compressedAudio=" +
                                    settings.compressedAudio +
                                    " adaptiveBuffer=" +
                                    settings!!.effectiveAdaptiveBuffer() +
                                    "\n"))
                                .toByteArray(StandardCharsets.UTF_8)
                        )
                    }
                } catch (ignored: IOException) {}
                if (devicePath != null) devicePath.close()
                closeSystemInput()
                if (recorder != null) {
                    try {
                        recorder.stop()
                    } catch (ignored: IllegalStateException) {}
                    recorder.release()
                }
                val response = ReceiverRejection.fromNative(NativeSender.receiverRejection())
                if (!destroyed && generation.get() == token) response!!.save(this)
                NativeSender.stop()
                if (zeroHal != null)
                    CaptureService.halCleanupUnconfirmed = !zeroHal.awaitCleanup(5000)
                if (localRoute != null) localRoute.close()
                if (networkBound) connectivity.bindProcessToNetwork(previousNetwork)
                val wasStarted: Boolean = started
                val message: Int = failure
                main.post(
                    Runnable {
                        if (destroyed || generation.get() != token) return@Runnable
                        BridgeState.connected = false
                        BridgeState.networkTiming = null
                        BridgeState.selected = null
                        BridgeState.status = message
                        BridgeState.receiverRejection = response
                        BridgeState.publish()
                        if (response!!.code !== 0)
                            getSystemService<NotificationManager?>(NotificationManager::class.java)
                                .notify(1, notification(response!!.message(this, false)))
                        if (!wasStarted) complete(token, false)
                    }
                )
            } finally {
                if (networkLease != null) networkLease.close()
                if (wake.isHeld()) wake.release()
            }
        }
    }

    private fun startSender(
        receiver: Receiver,
        settings: StreamSettings,
        dedicatedHal: Boolean,
        networkSettings: NetworkSettings,
    ) {
        NativeSender.start(
            receiver.host,
            receiver.port,
            receiver.name,
            receiver.deviceId,
            identity(),
            BuildConfig.VOLUME_LIMIT,
            settings.effectiveBufferMs(),
            settings.requestedPresentationMs(),
            settings.receiverTiming!!.independent,
            settings.requestedReceiverMinimumMs(),
            settings.requestedReceiverMaximumMs(),
            settings.packetFramesForBackend(dedicatedHal),
            settings.compressedAudio,
            settings.effectiveAdaptiveBuffer(),
            networkSettings.priority,
            true,
        )
    }

    fun disconnect() {
        cancelPending()
        generation.incrementAndGet()
        closeSystemInput()
        BridgeState.selected = null
        BridgeState.connected = false
        BridgeState.networkTiming = null
        BridgeState.status = R.string.sharing
        BridgeState.publish()
        getSystemService<NotificationManager?>(NotificationManager::class.java)
            .notify(1, notification(getString(R.string.sharing)))
    }

    private fun closeSystemInput() {
        val zeroHal: RootHalInput? = activeZeroHal
        activeZeroHal = null
        if (zeroHal != null) zeroHal.close()
        BridgeState.captureRegistration = null
        BridgeState.phonePath = null
        val devicePath: OwnedAudioPath? = activeDevicePath
        activeDevicePath = null
        if (devicePath != null) devicePath.close()
        val pipe: ParcelFileDescriptor? = systemPipe
        systemPipe = null
        if (pipe == null) {
            RootConnector.close(lifetime)
            return
        }
        try {
            pipe.close()
        } catch (ignored: IOException) {}
        RootConnector.close(lifetime)
    }

    private fun setBackend(token: Int, backend: BridgeState.CaptureBackend?) {
        main.post(
            Runnable {
                if (!destroyed && generation.get() == token) {
                    BridgeState.captureBackend = backend
                    BridgeState.publishTiming()
                }
            }
        )
    }

    private fun complete(token: Int, success: Boolean) {
        if (pendingCallback == null || callbackGeneration != token) return
        val callback: CaptureService.ConnectionCallback? = pendingCallback
        pendingCallback = null
        callback!!.finished(success)
    }

    private fun cancelPending() {
        if (pendingCallback != null) complete(callbackGeneration, false)
    }

    private fun identity(): String? {
        val preferences = getSharedPreferences("identity", Context.MODE_PRIVATE)
        var value = preferences.getString("device", null)
        if (value == null) {
            val random = UUID.randomUUID().toString().replace("-", "").substring(0, 12).uppercase()
            value =
                ("02:" +
                    random.substring(2, 4) +
                    ":" +
                    random.substring(4, 6) +
                    ":" +
                    random.substring(6, 8) +
                    ":" +
                    random.substring(8, 10) +
                    ":" +
                    random.substring(10, 12))
            preferences.edit().putString("device", value).apply()
        }
        return value
    }

    private fun notification(text: String?): Notification? {
        val manager = getSystemService<NotificationManager?>(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                "sharing",
                getString(R.string.channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.setDescription(getString(R.string.channel_description))
        manager.createNotificationChannel(channel)
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, CaptureService::class.java).setAction(CaptureService.STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return android.app.Notification.Builder(this, "sharing")
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                android.app.Notification.Action.Builder(null, getString(R.string.stop), stop)
                    .build()
            )
            .build()
    }

    override fun onDestroy() {
        if (wholeDeviceVolume != null) wholeDeviceVolume!!.close()
        destroyed = true
        cancelPending()
        generation.incrementAndGet()
        closeSystemInput()
        if (projection != null) {
            projection!!.unregisterCallback(projectionCallback)
            projection!!.stop()
            projection = null
        }
        BridgeState.capture = null
        BridgeState.selected = null
        BridgeState.connected = false
        BridgeState.pipeline = null
        BridgeState.networkTiming = null
        BridgeState.activePhoneTiming = null
        BridgeState.status = R.string.idle
        BridgeState.publish()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<String?>?) {
        writer.println("HomePod Bridge sender diagnostics (no recorded PCM)")
        writer.println(
            ("connected=" +
                BridgeState.connected +
                " volume=" +
                BridgeState.volume +
                " wholeDevice=" +
                StreamSettings.load(this)!!.wholeDevice)
        )
        writer.println(NativeSender.diagnostic())
        if (BridgeState.connectionTiming != null)
            writer.println(BridgeState.connectionTiming!!.summary())
        writer.println("networkTiming=" + NativeSender.network().contentToString())
        writer.println(
            "wifiRequest=" +
                (if (BridgeState.networkTiming == null) -1
                else BridgeState.networkTiming!!.wifiRequest)
        )
        writer.println(
            ("captureBackend=" +
                BridgeState.captureBackend +
                " dedicatedRequested=" +
                StreamSettings.load(this)!!.effectiveDedicatedCapture())
        )
        writer.println(
            ("dedicatedHalProbe=" +
                dedicatedHalProbe +
                " halQueuedFrames=" +
                halPendingFrames +
                " halNonce=" +
                halNonce +
                " captureScope=" +
                (if (dedicatedHalProbe) "self-root-zero" else "configured"))
        )
        writer.println(
            ("zeroProbe=" +
                zeroProbeActive +
                " zeroProbeFrames=" +
                zeroProbeFrames +
                " effectiveWholeDevice=" +
                (zeroProbeActive || StreamSettings.load(this)!!.wholeDevice))
        )
        val path = BridgeState.phonePath
        writer.println(
            ("deviceBufferRequested=" +
                StreamSettings.load(this)!!.effectiveDeviceBuffer() +
                " deviceBufferSuppressed=" +
                deviceBufferSuppressed +
                " phonePath=" +
                (if (path == null) "OFF" else path.state))
        )
        if (path != null) writer.println(path.report)
        val pipeline = BridgeState.pipeline
        if (pipeline != null) {
            writer.println("captureTiming=" + pipeline.capture!!.contentToString())
            writer.println("pipeTransitMicros=" + pipeline.pipeMicros)
        }
    }

    companion object {
        const val STOP: String = "dev.ryuya.homepodbridge.STOP"
        // Service recreation must wait for the previous native sender and route lease.
        private val worker: ExecutorService? = Executors.newSingleThreadExecutor()
        private val devicePaths: ExecutorService? = Executors.newSingleThreadExecutor()
        // The shared worker must not open another path after an unconfirmed dedicated close.
        @Volatile private var halCleanupUnconfirmed: Boolean = false

        @JvmStatic
        fun blockUnconfirmedHalCleanup() {
            CaptureService.halCleanupUnconfirmed = true
        }
    }
}
