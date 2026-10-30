package dev.ryuya.homepodbridge

import android.os.IBinder
import kotlin.jvm.JvmField

object RootProtocol {
    const val PACKAGE: String = "dev.ryuya.homepodbridge"
    @JvmField val AUTHORITY: String = RootProtocol.PACKAGE + ".system"
    @JvmField val DESCRIPTOR: String = RootProtocol.PACKAGE + ".SystemAudio"
    @JvmField val REQUEST: String = RootProtocol.PACKAGE + ".REQUEST_SYSTEM_BRIDGE"
    @JvmField val OPEN: Int = IBinder.FIRST_CALL_TRANSACTION
    @JvmField val CLOSE: Int = RootProtocol.OPEN + 1
    @JvmField val OPEN_CONFIGURED: Int = RootProtocol.CLOSE + 1
    @JvmField val VOLUME_ENDPOINT: Int = RootProtocol.OPEN_CONFIGURED + 1
    @JvmField val OPEN_TIMED: Int = RootProtocol.VOLUME_ENDPOINT + 1
    @JvmField val CAPTURE_TIMING: Int = RootProtocol.OPEN_TIMED + 1
    @JvmField val OPEN_SILENT_PROBE: Int = RootProtocol.CAPTURE_TIMING + 1
    @JvmField val OPEN_EXTERNAL: Int = RootProtocol.OPEN_SILENT_PROBE + 1
    @JvmField val VOLUME_CALLBACK: String = RootProtocol.PACKAGE + ".VolumeCallback"
    @JvmField val ADJUST_VOLUME: Int = IBinder.FIRST_CALL_TRANSACTION
    @JvmField val SET_VOLUME: Int = RootProtocol.ADJUST_VOLUME + 1
}
