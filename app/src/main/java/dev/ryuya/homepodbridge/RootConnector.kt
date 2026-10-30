package dev.ryuya.homepodbridge

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

object RootConnector {
    @JvmStatic
    @Throws(RemoteException::class)
    fun openExternal(lifetime: IBinder?, whole: Boolean): Boolean {
        val target = RootConnector.bridge
        if (target == null) return false
        val input = Parcel.obtain()
        val output = Parcel.obtain()
        try {
            input.writeInterfaceToken(RootProtocol.DESCRIPTOR)
            input.writeStrongBinder(lifetime)
            input.writeBoolean(whole)
            if (!target.transact(RootProtocol.OPEN_EXTERNAL, input, output, 0)) return false
            output.readException()
            RootConnector.captureBridges.put(lifetime, target)
            return true
        } finally {
            input.recycle()
            output.recycle()
        }
    }

    @Volatile private var bridge: IBinder? = null
    private val captureBridges: ConcurrentHashMap<IBinder?, IBinder?> =
        ConcurrentHashMap<IBinder?, IBinder?>()
    private val controls: ExecutorService? = Executors.newSingleThreadExecutor()

    @JvmStatic
    fun volumeEndpoint(lifetime: IBinder?, callback: IBinder?) {
        val target = RootConnector.captureBridges.getOrDefault(lifetime, RootConnector.bridge)
        if (target == null) return
        RootConnector.controls!!.execute(
            Runnable {
                val input = Parcel.obtain()
                val output = Parcel.obtain()
                try {
                    input.writeInterfaceToken(RootProtocol.DESCRIPTOR)
                    input.writeStrongBinder(lifetime)
                    input.writeStrongBinder(callback)
                    if (target.transact(RootProtocol.VOLUME_ENDPOINT, input, output, 0))
                        output.readException()
                } catch (ignored: RemoteException) {
                    // The ordinary remote session remains usable if a ROM lacks this API.
                } catch (ignored: RuntimeException) {} finally {
                    input.recycle()
                    output.recycle()
                }
            }
        )
    }

    @JvmStatic
    @Throws(RemoteException::class)
    fun register(value: IBinder?) {
        requireNotNull(value) { "Missing system bridge" }
        RootConnector.bridge = value
        value.linkToDeath(
            DeathRecipient {
                if (RootConnector.bridge === value) RootConnector.bridge = null
                RootConnector.captureBridges.entries.removeIf {
                    entry: MutableMap.MutableEntry<IBinder?, IBinder?>? ->
                    entry!!.value == value
                }
            },
            0,
        )
        Handler(Looper.getMainLooper()).post(Runnable { BridgeState.publish() })
    }

    @JvmStatic
    fun request(context: Context) {
        if (!RootConnector.available())
            context.sendBroadcast(Intent(RootProtocol.REQUEST).setPackage("com.android.systemui"))
    }

    @JvmStatic
    fun available(): Boolean {
        val value = RootConnector.bridge
        return value != null && value.isBinderAlive()
    }

    @JvmStatic
    fun captureTiming(lifetime: IBinder?): LongArray? {
        val target = RootConnector.captureBridges.getOrDefault(lifetime, RootConnector.bridge)
        if (target == null) return null
        val input = Parcel.obtain()
        val output = Parcel.obtain()
        try {
            input.writeInterfaceToken(RootProtocol.DESCRIPTOR)
            input.writeStrongBinder(lifetime)
            if (!target.transact(RootProtocol.CAPTURE_TIMING, input, output, 0)) return null
            output.readException()
            return output.createLongArray()
        } catch (ignored: RemoteException) {
            return null
        } catch (ignored: RuntimeException) {
            return null
        } finally {
            input.recycle()
            output.recycle()
        }
    }

    @JvmStatic
    @JvmOverloads
    @Throws(RemoteException::class)
    fun open(
        packageName: String?,
        lifetime: IBinder?,
        settings: StreamSettings,
        silentProbe: Boolean = false,
    ): RootConnector.Input {
        val target = RootConnector.bridge
        if (target == null) throw RemoteException("System bridge unavailable")
        val input = Parcel.obtain()
        val output = Parcel.obtain()
        try {
            input.writeInterfaceToken(RootProtocol.DESCRIPTOR)
            input.writeStrongBinder(lifetime)
            input.writeString(packageName)
            input.writeBoolean(settings.wholeDevice)
            input.writeInt(settings.effectiveBlockMs())
            input.writeBoolean(settings.lowLatency)
            val timed =
                target.transact(
                    if (silentProbe) RootProtocol.OPEN_SILENT_PROBE else RootProtocol.OPEN_TIMED,
                    input,
                    output,
                    0,
                )
            if (silentProbe && !timed)
                throw RemoteException("Reload SystemUI for silent capture diagnostics")
            if (!timed) {
                output.setDataSize(0)
                output.setDataPosition(0)
                if (!target.transact(RootProtocol.OPEN_CONFIGURED, input, output, 0))
                    throw RemoteException("Reload SystemUI to load the updated audio bridge")
            }
            output.readException()
            val pipe = ParcelFileDescriptor.CREATOR.createFromParcel(output)
            val opened =
                RootConnector.Input(
                    pipe,
                    timed,
                    if (output.dataAvail() > 0) output.readString() else null,
                )
            RootConnector.captureBridges.put(lifetime, target)
            return opened
        } finally {
            input.recycle()
            output.recycle()
        }
    }

    @JvmStatic
    fun close(lifetime: IBinder?) {
        var target = RootConnector.captureBridges.remove(lifetime)
        if (target == null) target = RootConnector.bridge
        if (target == null) return
        val input = Parcel.obtain()
        val output = Parcel.obtain()
        try {
            input.writeInterfaceToken(RootProtocol.DESCRIPTOR)
            input.writeStrongBinder(lifetime)
            if (target.transact(RootProtocol.CLOSE, input, output, 0)) output.readException()
        } catch (ignored: RemoteException) {} catch (ignored: RuntimeException) {} finally {
            input.recycle()
            output.recycle()
        }
    }

    @JvmRecord
    data class Input(
        val pipe: ParcelFileDescriptor?,
        val timed: Boolean,
        val registration: String?,
    ) {
        override fun toString(): String =
            "Input[pipe=$pipe, timed=$timed, registration=$registration]"
    }
}
