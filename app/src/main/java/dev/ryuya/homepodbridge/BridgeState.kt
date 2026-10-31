package dev.ryuya.homepodbridge

import android.os.Handler
import android.os.Looper
import dev.ryuya.homepodbridge.halprobe.PhoneTiming
import java.util.ArrayList
import java.util.LinkedHashSet
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/* In-process state only. No exported commands, credentials, PCM files, or network logs. */
object BridgeState {
    @JvmField var captureBackend: BridgeState.CaptureBackend? = BridgeState.CaptureBackend.STANDARD
    const val MUSIC: String = "com.apple.android.music"
    const val PACKAGE: String = "dev.ryuya.homepodbridge"

    private val MAIN: Handler = Handler(Looper.getMainLooper())
    private val OBSERVERS: MutableList<BridgeState.Observer?> = ArrayList<BridgeState.Observer?>()
    @JvmField var receivers: MutableList<Receiver?>? = java.util.List.of<Receiver?>()
    @JvmField val castCategories: MutableSet<String?> = LinkedHashSet<String?>()
    @JvmField var capture: CaptureService? = null
    @JvmField var selected: Receiver? = null
    @JvmField var connected: Boolean = false
    @JvmField var volume: Int = 0
    @JvmField var timing: IntArray? = null
    @JvmField var pipeline: PipelineTiming? = null
    @JvmField var networkTiming: NetworkTiming? = null
    @JvmField var connectionTiming: ConnectionTiming? = null
    @JvmField var activePhoneTiming: PhoneTiming? = null
    @JvmField @Volatile var captureRegistration: String? = null
    @JvmField @Volatile var phonePath: OwnedAudioPath.Update? = null
    @JvmField var status: Int = R.string.idle
    @JvmField var receiverRejection: ReceiverRejection? = ReceiverRejection.NONE

    @JvmStatic
    fun observe(observer: BridgeState.Observer) {
        BridgeState.OBSERVERS.add(observer)
        observer.changed()
    }

    @JvmStatic
    fun remove(observer: BridgeState.Observer?) {
        BridgeState.OBSERVERS.remove(observer)
    }

    @JvmStatic
    fun publish() {
        for (observer in ArrayList<BridgeState.Observer?>(BridgeState.OBSERVERS)) observer!!
            .changed()
    }

    @JvmStatic
    fun publishTiming() {
        for (observer in ArrayList<BridgeState.Observer?>(BridgeState.OBSERVERS)) observer!!
            .timingChanged()
    }

    @JvmStatic
    fun status(statusId: Int) {
        BridgeState.MAIN.post(
            Runnable {
                BridgeState.status = statusId
                BridgeState.publish()
            }
        )
    }

    enum class CaptureBackend {
        STANDARD,
        CHECKING,
        DEDICATED,
        DEDICATED_SHORT,
        DEDICATED_TEST,
        FALLBACK,
    }

    fun interface Observer {
        fun changed()

        fun timingChanged() {}
    }
}
