package dev.ryuya.homepodbridge

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.List
import java.util.function.Consumer
import kotlin.jvm.JvmOverloads

/* Bounded zero-input check through the production capture/sender; never starts a music app. */
class CommandPlayback(context: Context, status: Consumer<String?>) : AutoCloseable {
    private val context: Context
    private val status: Consumer<String?>
    private val main: Handler = Handler(Looper.getMainLooper())
    private var discovery: Discovery? = null
    private var epoch: Long = 0
    private var ownedService: CaptureService? = null
    private var ownedLifetime: IBinder? = null
    private var selectionPending: Boolean = false
    private var dedicatedHal: Boolean = false
    private var acoustic: AcousticProbe? = null
    private var testWhole: Boolean = false
    private var pulse: Boolean = false
    private var normal: Boolean = false

    init {
        this.context = context
        this.status = status
    }

    fun startAcoustic(probe: AcousticProbe?) {
        start(true, false, false, false, probe)
    }

    @JvmOverloads
    fun start(
        dedicatedHal: Boolean = false,
        whole: Boolean = StreamSettings.load(context)!!.wholeDevice,
        pulse: Boolean = false,
        normal: Boolean = false,
    ) {
        start(dedicatedHal, whole, pulse, normal, null)
    }

    private fun start(
        dedicatedHal: Boolean,
        whole: Boolean,
        pulse: Boolean,
        normal: Boolean,
        probe: AcousticProbe?,
    ) {
        if (BridgeState.capture != null) {
            status.accept("Existing sharing must stop before the test")
            if (probe != null) probe.close()
            return
        }
        if (!RootConnector.available()) {
            status.accept("Zero-input transfer requires the system bridge")
            if (probe != null) probe.close()
            return
        }
        close()
        this.acoustic = probe
        this.dedicatedHal = dedicatedHal
        this.testWhole = whole
        this.pulse = pulse
        this.normal = normal
        selectionPending = false
        val token: Long = ++epoch
        status.accept("Discovering one HomePod for a muted zero-input transfer")
        discovery =
            Discovery(
                context,
                object : Discovery.Listener {
                    override fun changed(receivers: MutableList<Receiver?>?) {
                        if (token != epoch || selectionPending || receivers!!.isEmpty()) return
                        if (receivers!!.size != 1) {
                            status.accept("Ambiguous receivers; no transfer started")
                            close()
                            return
                        }
                        val receiver = receivers!!.get(0)
                        selectionPending = true
                        if (discovery != null) {
                            discovery!!.close()
                            discovery = null
                        }
                        BridgeState.receivers = List.copyOf<Receiver?>(receivers)
                        BridgeState.publish()
                        try {
                            context.startForegroundService(
                                Intent(context, CaptureService::class.java).putExtra("system", true)
                            )
                            waitForService(receiver, token, SystemClock.elapsedRealtime() + 3000)
                        } catch (error: RuntimeException) {
                            status.accept(
                                "Transfer start unavailable: " + error.javaClass.getSimpleName()
                            )
                            close()
                        }
                    }

                    override fun failed() {
                        if (token == epoch) {
                            status.accept("Receiver discovery unavailable")
                            close()
                        }
                    }
                },
            )
        discovery!!.start()
        main.postDelayed(
            Runnable {
                if (token == epoch) {
                    close()
                    status.accept("Bounded zero-input transfer ended")
                }
            },
            25000,
        )
    }

    private fun waitForService(receiver: Receiver?, token: Long, deadline: Long) {
        if (token != epoch) return
        val service = BridgeState.capture
        if (service != null) {
            ownedService = service
            val finished = CaptureService.ConnectionCallback { success: Boolean ->
                if (token == epoch && acoustic != null) acoustic!!.connected(success)
                if (token == epoch)
                    status.accept(
                        if (success) "Zero-input transfer connected at initial mute"
                        else "Zero-input transfer failed"
                    )
            }
            if (normal) service.select(receiver!!, BridgeState.MUSIC, finished)
            else if (dedicatedHal)
                service.selectZeroHalProbe(receiver!!, finished, testWhole, pulse)
            else service.selectZeroProbe(receiver!!, finished)
            ownedLifetime = service.lifetime()
            if (acoustic != null) acoustic!!.bind(ownedLifetime)
            return
        }
        if (SystemClock.elapsedRealtime() >= deadline) {
            status.accept("Capture service unavailable")
            close()
            return
        }
        main.postDelayed(Runnable { waitForService(receiver, token, deadline) }, 50)
    }

    override fun close() {
        ++epoch
        if (acoustic != null) {
            val closing: AcousticProbe? = acoustic
            acoustic = null
            closing!!.close()
        }
        if (discovery != null) {
            discovery!!.close()
            discovery = null
        }
        if (
            ownedService != null &&
                BridgeState.capture == ownedService &&
                ownedService!!.lifetime() === ownedLifetime
        ) {
            ownedService!!.disconnect()
            context.stopService(Intent(context, CaptureService::class.java))
        }
        ownedService = null
        ownedLifetime = null
    }
}
