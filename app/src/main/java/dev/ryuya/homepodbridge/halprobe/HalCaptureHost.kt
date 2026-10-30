package dev.ryuya.homepodbridge.halprobe

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.LocalSocketAddress.Namespace
import android.os.Looper
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import kotlin.jvm.JvmStatic
import org.json.JSONObject

/* Root policy owner for a single authenticated app socket, including idle/resume lifetimes. */
object HalCaptureHost {
    @JvmStatic
    fun main(args: Array<String>) {
        val result = JSONObject()
        var allocations: Boolean = false
        var state: ReadOnlyHalState? = null
        var baseline: String? = null
        var registration: String? = null
        val periods = arrayOf<HalPeriodExperiment?>(null, null)
        try {
            if (
                (args.size != 3 && args.size != 5) ||
                    (UUID.fromString(args[0]).toString() != args[0]) ||
                    !args[2].matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+".toRegex()) ||
                    args[2].length > 255
            )
                throw SecurityException()
            HalPortProbe.rootPreflight()
            state = ReadOnlyHalState()
            baseline = state.snapshot().toString()
            if (Looper.myLooper() == null) Looper.prepare()
            val activity = Class.forName("android.app.ActivityThread")
            val thread = activity.getMethod("systemMain").invoke(null)
            val context: Context? =
                activity.getMethod("getSystemContext").invoke(thread) as Context?
            val bridgeUid =
                context!!.getPackageManager().getApplicationInfo("dev.ryuya.homepodbridge", 0).uid
            val selectedUid = context!!.getPackageManager().getApplicationInfo(args[2], 0).uid
            if (selectedUid == bridgeUid) throw SecurityException("Cannot capture Bridge itself")
            val scope = CaptureScope.parse(args[1], selectedUid, bridgeUid)
            val shortPeriod = args[1].endsWith("-short")
            if (args.size == 5 && !shortPeriod) throw SecurityException()
            val timing: PhoneTiming? =
                if (args.size == 5) PhoneTiming(args[3].toInt(), args[4].toInt())
                else PhoneTiming.fastest()
            val target: Int =
                when (args[1]) {
                    "test-target-752" -> 752
                    "test-target-768" -> 768
                    else -> 800
                }
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress("hpb-live-" + args[0], Namespace.ABSTRACT))
                if (socket.getPeerCredentials().getUid() != bridgeUid)
                    throw SecurityException("Bridge client required")
                val stopped = AtomicBoolean()
                val control =
                    Thread(
                        Runnable {
                            try {
                                socket.getInputStream().read()
                            } catch (ignored: IOException) {} finally {
                                stopped.set(true)
                                try {
                                    socket.close()
                                } catch (ignored: IOException) {}
                            }
                        },
                        "HomePod-HAL-client",
                    )
                control.setDaemon(true)
                control.start()
                allocations = true
                (if (shortPeriod)
                        HalQueueMapping.PreparedWorker(
                            args[0],
                            if (scope!!.zeroOnly) ProbeScenario.LIVE_ZERO else ProbeScenario.LIVE,
                        )
                    else null)
                    .use { prepared ->
                        RegisteredPolicy(
                                context!!,
                                scope!!,
                                RegisteredPolicy.BeforeRoute { address: String? ->
                                    if (shortPeriod) {
                                        if (stopped.get()) throw IOException("Client stopped")
                                        periods[0] = HalPeriodExperiment(address, false, timing!!)
                                        periods[1] = HalPeriodExperiment(address, true, timing!!)
                                    }
                                },
                            )
                            .use { policy ->
                                registration = policy.address
                                val output = socket.getOutputStream()
                                val ready = HalPortProbe.Ready {
                                    val message = DataOutputStream(output)
                                    message.writeInt(0x48505231)
                                    message.writeUTF(args[0])
                                    message.writeUTF(policy.address)
                                    message.flush()
                                }
                                if (shortPeriod) {
                                    result.put(
                                        "shortPeriod",
                                        HalPeriodExperiment.requireOutput(
                                            state,
                                            policy.address,
                                            timing!!,
                                        ),
                                    )
                                    result.put(
                                        "capture",
                                        HalPortProbe.livePeriodCapture(
                                            policy.address,
                                            UUID.fromString(args[0]),
                                            scope.zeroOnly,
                                            scope.pulse,
                                            output,
                                            BooleanSupplier { stopped.get() },
                                            ready,
                                            socket.getFileDescriptor(),
                                            HalPortProbe.Ready {
                                                periods[1]!!.close()
                                                periods[0]!!.close()
                                                if (stopped.get())
                                                    throw IOException("Client stopped")
                                            },
                                            timing!!,
                                        ),
                                    )
                                } else {
                                    result.put(
                                        "capture",
                                        HalPortProbe.inputSession(
                                            policy.address,
                                            UUID.fromString(args[0]),
                                            target,
                                            false,
                                            true,
                                            scope.zeroOnly,
                                            scope.pulse,
                                            output,
                                            BooleanSupplier { stopped.get() },
                                            ready,
                                            socket.getFileDescriptor(),
                                        ),
                                    )
                                }
                            }
                    }
            }
        } catch (failure: Throwable) {
            try {
                result.put("failure", failure.javaClass.getSimpleName())
            } catch (ignored: Exception) {}
        }
        for (i in periods.indices.reversed()) if (periods[i] != null)
            try {
                periods[i]!!.close()
            } catch (error: Exception) {
                try {
                    result.put("periodCleanupFailure", error.javaClass.getSimpleName())
                } catch (ignored: Exception) {}
            }
        var periodClean: Boolean = true
        for (period in periods) if (period != null && !period.cleanupConfirmed())
            periodClean = false
        try {
            result.put("periodCleanupConfirmed", periodClean)
        } catch (ignored: Exception) {}
        try {
            var restored: Boolean = !allocations
            var ownedReleased: Boolean = !allocations
            if (allocations && state != null)
                for (i in 0..59) {
                    val after = state.snapshot()
                    restored = baseline == after.toString()
                    var ownPort: Boolean = false
                    val ports = after!!.getJSONArray("ports")
                    for (j in 0 until ports.length()) if (
                        registration != null &&
                            registration == ports.getJSONObject(j).optString("address")
                    )
                        ownPort = true
                    val capture = result.optJSONObject("capture")
                    ownedReleased =
                        registration != null &&
                            !ownPort &&
                            capture != null &&
                            capture.optBoolean("ownedInputReleased")
                    if (restored || ownedReleased) break
                    Thread.sleep(50)
                }
            result
                .put("resourcesRestoredExactly", restored)
                .put("ownedResourcesReleased", ownedReleased)
                .put("noAllocations", !allocations)
            if (!restored && baseline != null)
                result.put("before", JSONObject(baseline)).put("after", state!!.snapshot())
        } catch (uncertain: Exception) {
            try {
                result.put("resourcesRestoredExactly", false)
            } catch (ignored: Exception) {}
        }
        println(result)
        System.out.flush()
        if (
            periodClean &&
                (result.optBoolean("resourcesRestoredExactly") ||
                    result.optBoolean("ownedResourcesReleased"))
        )
            System.exit(0)
    }
}
