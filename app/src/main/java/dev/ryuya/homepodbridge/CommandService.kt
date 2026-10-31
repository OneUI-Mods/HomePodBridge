package dev.ryuya.homepodbridge

import android.app.Notification.Builder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.ryuya.homepodbridge.halprobe.PhoneTiming
import dev.ryuya.homepodbridge.halprobe.ProbeScenario
import java.io.FileDescriptor
import java.io.IOException
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.concurrent.Volatile

/* DUMP-protected diagnostics; an explicit bounded acoustic command is the only audible test. */
class CommandService : Service() {
    private val commands: ExecutorService? = Executors.newSingleThreadExecutor()
    @Volatile private var commandStatus: String = "Ready"
    private val main: Handler = Handler(Looper.getMainLooper())
    private var playback: CommandPlayback? = null
    private var acoustic: AcousticProbe? = null
    private var acousticEpoch: Long = 0

    override fun onCreate() {
        super.onCreate()
        playback = CommandPlayback(this, Consumer { value: String? -> commandStatus = (value)!! })
        val manager = getSystemService<NotificationManager?>(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                "timing-diagnostics",
                getString(R.string.tab_diagnostics),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)
        startForeground(
            2,
            Builder(this, channel.getId())
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.diagnostics_command_running))
                .setSmallIcon(R.drawable.ic_speaker)
                .setSound(null)
                .setVibrate(LongArray(0))
                .setDefaults(0)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        RootConnector.request(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command: String? = if (intent == null) "status" else intent.getStringExtra("command")
        if (command == null || command == "status") return Service.START_NOT_STICKY
        if (command == "network-priority-on" || command == "network-priority-off") {
            if (BridgeState.capture != null || BridgeState.connected) {
                commandStatus = "Stop sharing before changing network priority"
                return Service.START_NOT_STICKY
            }
            NetworkSettings(command.endsWith("on")).save(this)
            commandStatus = "Network priority preference saved"
            return Service.START_NOT_STICKY
        }
        if (command == "measure-acoustic" || command == "measure-acoustic-paired") {
            val paired: Boolean = command == "measure-acoustic-paired"
            val volume = intent!!.getIntExtra("volume", -1)
            if (
                volume < 1 ||
                    volume > AcousticProbe.MAX_DIAGNOSTIC_VOLUME ||
                    BridgeState.capture != null ||
                    BridgeState.connected
            ) {
                commandStatus = "ACOUSTIC_REFUSED busy or invalid volume"
                return Service.START_NOT_STICKY
            }
            if (acoustic != null && !acoustic!!.finished()) {
                commandStatus = "ACOUSTIC_REFUSED microphone busy"
                return Service.START_NOT_STICKY
            }
            if (acoustic != null) acoustic!!.clear()
            try {
                val notification =
                    Builder(this, "timing-diagnostics")
                        .setContentTitle(
                            getString(
                                if (paired) R.string.acoustic_paired_title
                                else R.string.acoustic_title
                            )
                        )
                        .setContentText(
                            getString(
                                if (paired) R.string.acoustic_paired_description
                                else R.string.acoustic_description
                            )
                        )
                        .setSmallIcon(R.drawable.ic_speaker)
                        .setSound(null)
                        .setVibrate(LongArray(0))
                        .setDefaults(0)
                        .setOnlyAlertOnce(true)
                        .setOngoing(true)
                        .build()
                startForeground(
                    2,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                val token: Long = ++acousticEpoch
                acoustic =
                    AcousticProbe(
                        this,
                        volume,
                        paired,
                        Consumer { value: String? ->
                            if (acousticEpoch == token) commandStatus = (value)!!
                        },
                        Runnable {
                            if (acousticEpoch == token) {
                                playback!!.close()
                                startForeground(
                                    2,
                                    notification,
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                                )
                            }
                        },
                    )
                val prepared: AcousticProbe? = acoustic
                commands!!.execute(
                    Runnable {
                        try {
                            val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                            while (
                                !RootConnector.available() && System.nanoTime() < deadline
                            ) Thread.sleep(25)
                            main.post(
                                Runnable {
                                    if (acousticEpoch != token) return@Runnable
                                    if (RootConnector.available())
                                        playback!!.startAcoustic(prepared)
                                    else {
                                        commandStatus = "System bridge unavailable"
                                        prepared!!.connected(false)
                                    }
                                }
                            )
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            prepared!!.close()
                        }
                    }
                )
            } catch (failure: RuntimeException) {
                commandStatus =
                    "ACOUSTIC_FAILED " + failure.javaClass.getSimpleName() + ": " + failure.message
            }
            return Service.START_NOT_STICKY
        }
        if (command == "clear-acoustic") {
            ++acousticEpoch
            if (acoustic != null) acoustic!!.clear()
            playback!!.close()
            commandStatus = "ACOUSTIC_CLEARED"
            return Service.START_NOT_STICKY
        }

        if (command == "short-mixer-on" || command == "short-mixer-off") {
            if (BridgeState.capture != null || BridgeState.connected || BridgeState.volume != 0) {
                commandStatus = "Stop sharing before changing the mixer period"
                return Service.START_NOT_STICKY
            }
            StreamSettings.load(this)!!.withShortMixerPeriod(command.endsWith("on"))!!.save(this)
            commandStatus = "Short mixer preference: " + command.endsWith("on")
            return Service.START_NOT_STICKY
        }
        if (command == "check-manual-settings") {
            if (BridgeState.capture != null || BridgeState.connected || BridgeState.volume != 0) {
                commandStatus = "Manual check busy"
                return Service.START_NOT_STICKY
            }
            commandStatus = ManualSettingsCheck.run(this)!!
            return Service.START_NOT_STICKY
        }
        if (command == "manual-packet-size") {
            val settings = StreamSettings.load(this)
            val frames = intent!!.getIntExtra("frames", -1)
            if (
                BridgeState.capture != null ||
                    BridgeState.connected ||
                    BridgeState.volume != 0 ||
                    !settings!!.manualMode ||
                    (frames != 44 && frames != 88 && frames != 352)
            ) {
                commandStatus = "Packet size requires idle manual mode and 44, 88 or 352 frames"
                return Service.START_NOT_STICKY
            }
            settings!!
                .withShortPacketFrames(if (frames == 44) 44 else 88)!!
                .withShortAudioPackets(frames != 352)!!
                .save(this)
            commandStatus = "Manual packet size saved"
            return Service.START_NOT_STICKY
        }
        if (command == "manual-phone-timing") {
            val settings = StreamSettings.load(this)
            if (
                BridgeState.capture != null ||
                    BridgeState.connected ||
                    BridgeState.volume != 0 ||
                    !settings!!.manualMode
            ) {
                commandStatus = "Stop sharing and select manual mode before changing phone timing"
                return Service.START_NOT_STICKY
            }
            try {
                val timing =
                    PhoneTiming(
                        intent!!.getIntExtra("period_ms", -1),
                        intent!!.getIntExtra("reserve_frames", -1),
                    )
                settings!!.withPhoneTiming(timing)!!.save(this)
                commandStatus = "Manual phone timing saved"
            } catch (invalid: IllegalArgumentException) {
                commandStatus = "Invalid manual phone timing"
            }
            return Service.START_NOT_STICKY
        }
        if (
            command == "probe-local-timing-min" ||
                command == "probe-local-timing-mid" ||
                command == "probe-local-timing-max"
        ) {
            val timing: PhoneTiming? =
                if (command.endsWith("min")) PhoneTiming.fastest()
                else if (command.endsWith("mid")) PhoneTiming(11, 448) else PhoneTiming(21, 800)
            commandStatus = "Checking bounded phone timing with fixed zeros"
            commands!!.execute(
                Runnable { commandStatus = "Local HAL: " + LocalHalProbe.runTiming(this, timing!!) }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "probe-owned-period") {
            val period = intent!!.getIntExtra("periodMs", -1)
            commandStatus = "Checking owned period without playback"
            commands!!.execute(
                Runnable {
                    commandStatus = "HAL period: " + HalTransportProbe.ownedPeriod(this, period)
                }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "probe-local-manual-timing") {
            commandStatus = "Checking manual phone timing with fixed zeros"
            val timing = StreamSettings.load(this)!!.phoneTiming
            commands!!.execute(
                Runnable { commandStatus = "Local HAL: " + LocalHalProbe.runTiming(this, timing!!) }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "probe-local-short-period" || command == "probe-local-short-period-cycle") {
            commandStatus = "Checking the actual short-period app path with fixed zeros"
            commands!!.execute(
                Runnable {
                    commandStatus =
                        "Local HAL: " +
                            LocalHalProbe.run(this, 800, command.endsWith("cycle"), true)
                }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "probe-period-capture") {
            commandStatus = "Checking fixed-zero input with a short owned mixer period"
            commands!!.execute(
                Runnable { commandStatus = "HAL period: " + HalTransportProbe.periodCapture(this) }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "probe-export-audioserver") {
            commandStatus = "Reading the fixed system audio server image"
            commands!!.execute(
                Runnable {
                    commandStatus =
                        "Audio server image: " + HalTransportProbe.exportAudioServer(this)
                }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "probe-hal-period" || command == "probe-mixer-period") {
            commandStatus = "Checking owned HAL period without playback"
            commands!!.execute(
                Runnable {
                    commandStatus =
                        ("HAL period: " +
                            HalTransportProbe.getterPeriod(this, command == "probe-mixer-period"))
                }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "probe-output-period") {
            commandStatus = "Checking private virtual output period without playback"
            commands!!.execute(
                Runnable {
                    commandStatus = "Output period: " + HalTransportProbe.outputPeriod(this)
                }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "probe-packet-handoff") {
            commandStatus = "Checking zero-only packet assembly without network output"
            commands!!.execute(
                Runnable { commandStatus = "Packet handoff: " + PacketHandoffProbe.run(this) }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "short-packets-on" || command == "short-packets-off") {
            if (BridgeState.capture != null || BridgeState.connected || BridgeState.volume != 0) {
                commandStatus = "Stop sharing before changing diagnostic packet preference"
                return Service.START_NOT_STICKY
            }
            StreamSettings.load(this)!!.withShortAudioPackets(command.endsWith("on"))!!.save(this)
            commandStatus = "Short packet preference: " + command.endsWith("on")
            return Service.START_NOT_STICKY
        }
        if (
            java.util.List.of<String?>(
                    "probe-local-hal-steady",
                    "probe-local-hal-752",
                    "probe-local-hal-768",
                )
                .contains(command)
        ) {
            val target: Int =
                if (command.endsWith("752")) 752 else if (command.endsWith("768")) 768 else 800
            commandStatus = "Checking fixed zero-only reserve candidate"
            commands!!.execute(
                Runnable { commandStatus = "Local HAL: " + LocalHalProbe.run(this, target, false) }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "probe-local-hal") {
            commandStatus = "Checking zero-only capture without a receiver"
            commands!!.execute(Runnable { commandStatus = "Local HAL: " + LocalHalProbe.run(this) })
            return Service.START_NOT_STICKY
        }
        if (command == "timing-independent" || command == "timing-linked") {
            if (BridgeState.capture != null || BridgeState.connected || BridgeState.volume != 0) {
                commandStatus = "Stop sharing before changing diagnostic timing"
                return Service.START_NOT_STICKY
            }
            val settings = StreamSettings.load(this)
            var timing = ReceiverTiming.linked(settings!!.effectiveLatencyMs())
            if (command == "timing-independent") {
                val minimum = intent!!.getIntExtra("minimum_ms", -1)
                val maximum = intent!!.getIntExtra("maximum_ms", -1)
                val presentation = intent!!.getIntExtra("presentation_ms", -1)
                if (
                    minimum < 10 ||
                        maximum > 2000 ||
                        minimum > maximum ||
                        presentation < 10 ||
                        presentation > 2000
                ) {
                    commandStatus = "Invalid independent timing"
                    return Service.START_NOT_STICKY
                }
                timing = ReceiverTiming(true, minimum, maximum, presentation)
            }
            settings!!.withReceiverTiming(timing)!!.save(this)
            commandStatus = "Independent receiver timing: " + timing!!.independent
            return Service.START_NOT_STICKY
        }
        if (command == "probe-policy-input" || command == "probe-policy-route") {
            commandStatus = "Preparing record-free self-UID policy input"
            val routeOnly: Boolean = command == "probe-policy-route"
            commands!!.execute(
                Runnable { commandStatus = (HalTransportProbe.policyInput(this, routeOnly))!! }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "stop-hal-probe") {
            val nonce = intent!!.getStringExtra("owned_nonce")
            commandStatus = "Stopping specified prototype helper"
            commands!!.execute(Runnable { commandStatus = (HalTransportProbe.stop(this, nonce))!! })
            return Service.START_NOT_STICKY
        }
        if (command == "probe-hal-metrics") {
            commandStatus = "Reading idle HAL resource counts"
            commands!!.execute(Runnable { commandStatus = (HalTransportProbe.metrics(this))!! })
            return Service.START_NOT_STICKY
        }
        if (ProbeScenario.forCommand(command) != null) {
            commandStatus = "Preparing open-only HAL transport probe"
            val requested: String? = command
            commands!!.execute(
                Runnable { commandStatus = (HalTransportProbe.run(this, requested))!! }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "recover-audio") {
            val nonce = intent!!.getStringExtra("owned_nonce")
            commandStatus = "Checking explicit audio-service recovery"
            commands!!.execute(
                Runnable { commandStatus = (AudioServiceRecovery.restart(this, nonce))!! }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "reload-status") {
            commandStatus = "Checking GalaxyReload CLI"
            commands!!.execute(
                Runnable { commandStatus = "GalaxyReload CLI: " + GalaxyReloadCli.check(this) }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "reload-systemui") {
            commandStatus = "Reloading through GalaxyReload CLI"
            commands!!.execute(
                Runnable {
                    commandStatus = "GalaxyReload CLI: " + GalaxyReloadCli.reload(this)
                    main.post(Runnable { RootConnector.request(this) })
                }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "hal-capture-on" || command == "hal-capture-off") {
            StreamSettings.load(this)!!.withDedicatedCapture(command.endsWith("on"))!!.save(this)
            commandStatus = "Dedicated capture preference: " + command.endsWith("on")
            return Service.START_NOT_STICKY
        }
        if (command == "capture-scope-whole" || command == "capture-scope-selected") {
            if (BridgeState.capture != null) {
                commandStatus = "Stop sharing before changing diagnostic scope"
                return Service.START_NOT_STICKY
            }
            StreamSettings.load(this)!!.withWholeDevice(command.endsWith("whole"))!!.save(this)
            commandStatus = "Whole-device capture preference: " + command.endsWith("whole")
            return Service.START_NOT_STICKY
        }
        if (
            java.util.List.of<String?>(
                    "connect-zero",
                    "connect-zero-hal",
                    "connect-zero-hal-whole",
                    "connect-zero-hal-cycle",
                    "connect-idle-hal",
                )
                .contains(command)
        ) {
            val dedicated = command.startsWith("connect-zero-hal")
            val whole: Boolean = command == "connect-zero-hal-whole"
            val pulse: Boolean = command == "connect-zero-hal-cycle"
            val normal: Boolean = command == "connect-idle-hal"
            commands!!.execute(
                Runnable {
                    try {
                        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        while (!RootConnector.available() && System.nanoTime() < deadline) Thread
                            .sleep(25)
                        if (RootConnector.available())
                            main.post(
                                Runnable { playback!!.start(dedicated, whole, pulse, normal) }
                            )
                        else commandStatus = "System bridge unavailable"
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            )
            return Service.START_NOT_STICKY
        }
        if (command == "stop-transfer") {
            playback!!.close()
            commandStatus = "Command-owned transfer stopped"
            return Service.START_NOT_STICKY
        }
        if (command == "phone-buffer-on" || command == "phone-buffer-off") {
            StreamSettings.load(this)!!.withDeviceBuffer(command.endsWith("on"))!!.save(this)
            commandStatus = "Phone buffer reduction preference: " + command.endsWith("on")
            return Service.START_NOT_STICKY
        }
        if (command == "phone-buffer-balanced" || command == "phone-buffer-faster") {
            val frames: Int = if (command.endsWith("faster")) 928 else 1856
            StreamSettings.load(this)!!.withDeviceBufferFrames(frames)!!.save(this)
            commandStatus = "Phone buffer target preference: " + frames
            return Service.START_NOT_STICKY
        }
        if (
            !java.util.List.of<String?>("baseline", "guard", "tuned", "lease", "brief", "minimum")
                .contains(command)
        ) {
            commandStatus = "Unknown diagnostic command"
            return Service.START_NOT_STICKY
        }
        commandStatus = "Preparing " + command
        val requested: String? = command
        commands!!.execute(
            Runnable {
                try {
                    val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (!RootConnector.available() && System.nanoTime() < deadline) Thread.sleep(
                        25
                    )
                    if (!RootConnector.available()) {
                        commandStatus = "System bridge unavailable"
                        return@Runnable
                    }
                    CaptureProbe.request(
                        this,
                        when (requested) {
                            "guard" -> 1
                            "tuned" -> 2
                            "lease" -> 3
                            "brief" -> 4
                            "minimum" -> 5
                            else -> 0
                        },
                    )
                    commandStatus = "Dispatched " + requested
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    commandStatus = "Command cancelled"
                }
            }
        )
        return Service.START_NOT_STICKY
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<String?>?) {
        if (
            args != null &&
                args.size == 1 &&
                (args[0] == "--acoustic-record" || args[0] == "--acoustic-summary")
        ) {
            if (acoustic == null) writer.println("ACOUSTIC_NONE")
            else acoustic!!.dump(writer, args[0] == "--acoustic-record")
            return
        }
        writer.println("Command service: " + commandStatus)
        writer.println("connected=" + BridgeState.connected + " volume=" + BridgeState.volume)
        writer.println("systemBridgeReady=" + RootConnector.available())
        if (BridgeState.connectionTiming != null)
            writer.println(BridgeState.connectionTiming!!.summary())
        writer.println("networkPriorityPreference=" + NetworkSettings.load(this)!!.priority)
        writer.println("networkTiming=" + NativeSender.network().contentToString())
        writer.println("senderPipeline=" + NativeSender.pipeline().contentToString())
        writer.println(
            "wifiRequest=" +
                (if (BridgeState.networkTiming == null) -1
                else BridgeState.networkTiming!!.wifiRequest)
        )
        val receiverResponse: ReceiverRejection? =
            if (BridgeState.connected) BridgeState.receiverRejection
            else ReceiverRejection.load(this)
        writer.println(
            ("receiverRejection=" +
                receiverResponse!!.code +
                " terminal=" +
                receiverResponse!!.terminal)
        )
        writer.println("receiverMessage=" + receiverResponse!!.message(this, BridgeState.connected))
        writer.println(
            ("captureBackend=" +
                BridgeState.captureBackend +
                " dedicatedPreference=" +
                StreamSettings.load(this)!!.dedicatedCapture)
        )
        try {
            openFileInput("last-hal-capture.txt").use { report ->
                writer.println(
                    "Last HAL capture: " + String(report.readNBytes(16384), StandardCharsets.UTF_8)
                )
            }
        } catch (absent: IOException) {}
        try {
            openFileInput("last-hal-zero.txt").use { report ->
                writer.println(
                    "Last zero HAL result: " +
                        String(report.readNBytes(8192), StandardCharsets.UTF_8)
                )
            }
        } catch (absent: IOException) {}
        for (name in java.util.List.of<String?>("last-capture-error.txt", "last-connection.txt")) {
            try {
                openFileInput(name).use { report ->
                    writer.println(
                        (name + ": " + String(report.readNBytes(8192), StandardCharsets.UTF_8))
                    )
                }
            } catch (absent: IOException) {}
        }
        val settings = StreamSettings.load(this)
        writer.println(
            ("shortAudioPackets=" +
                settings!!.shortAudioPackets +
                " requestedPacketFrames=" +
                settings!!.requestedPacketFrames())
        )
        writer.println(
            ("independentReceiverTiming=" +
                settings.receiverTiming!!.independent +
                " requestedReceiverMinimumMs=" +
                settings!!.requestedReceiverMinimumMs() +
                " requestedReceiverMaximumMs=" +
                settings!!.requestedReceiverMaximumMs() +
                " requestedPresentationMs=" +
                settings!!.requestedPresentationMs())
        )
        writer.println(
            ("wholeDevice=" +
                settings.wholeDevice +
                " deviceBuffer=" +
                settings.deviceBuffer +
                " effectiveDeviceBuffer=" +
                settings!!.effectiveDeviceBuffer() +
                " deviceBufferFrames=" +
                settings.deviceBufferFrames +
                " receiverTargetMs=" +
                settings!!.effectiveLatencyMs())
        )
        writer.println("shortMixerPeriod=" + settings.shortMixerPeriod)
        writer.println(
            ("manualMode=" +
                settings.manualMode +
                " phonePeriodMs=" +
                settings.phoneTiming!!.periodMs +
                " phonePeriodFrames=" +
                settings.phoneTiming!!.periodFrames() +
                " phoneReserveFrames=" +
                settings.phoneTiming.reserveFrames)
        )
        CaptureProbe.dump(writer)
        HalInspection.dump(writer, false)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        ++acousticEpoch
        if (acoustic != null) acoustic!!.clear()
        commands!!.shutdownNow()
        playback!!.close()
        CaptureProbe.cancel()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
