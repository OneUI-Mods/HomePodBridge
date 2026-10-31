package dev.ryuya.homepodbridge

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.Process
import java.io.IOException
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized

/* Bounded diagnostics using the normal owned capture path, without a sender or source playback. */
object CaptureProbe {
    private var running: Boolean = false
    private var status: String = "Not requested"
    private var cancellation: Runnable? = null

    @JvmStatic
    fun cancel() {
        val action: Runnable?
        synchronized(CaptureProbe::class.java) {
            action = CaptureProbe.cancellation
        }
        if (action != null) Thread(action, "HomePod-probe-cancel").start()
    }

    @JvmStatic
    @Synchronized
    fun request(context: Context) {
        CaptureProbe.request(context, 0)
    }

    @JvmStatic
    @Synchronized
    fun request(context: Context, freeze: Boolean) {
        CaptureProbe.request(context, if (freeze) 1 else 0)
    }

    @JvmStatic
    @Synchronized
    fun request(context: Context, operation: Int) {
        if (operation < 0 || operation > 5) return
        if (CaptureProbe.running) return
        if (BridgeState.capture != null || !RootConnector.available()) {
            CaptureProbe.status = "Probe requires idle sharing and an available system bridge"
            return
        }
        CaptureProbe.running = true
        CaptureProbe.status = "Capture-only probe running; no network sender or playback command"
        val app = context.getApplicationContext()
        Thread(Runnable { CaptureProbe.run(app, operation) }, "HomePod-capture-probe").start()
    }

    @JvmStatic
    private fun run(context: Context?, operation: Int) {
        val lifetime = Binder()
        var descriptor: ParcelFileDescriptor? = null
        val timer = Executors.newSingleThreadScheduledExecutor()
        val leaseExecutor = Executors.newSingleThreadExecutor()
        var lease: OwnedAudioPath? = null
        val leaseUpdate = AtomicReference<OwnedAudioPath.Update?>()
        val ages = ArrayList<Long>()
        var frames: Long = 0
        var zeroSourceFrames: Long = 0
        var lastTiming: LongArray? = null
        var timingPolls: Int = 0
        var missingTimingPolls: Int = 0
        var tuningReport: String = ""
        var restoreReport: String = ""
        val tuneOperation: Int = if (operation == 4) 4 else if (operation == 5) 6 else operation
        val restoreOperation: Int = if (operation == 4) 5 else if (operation == 5) 7 else 3
        val targetFrames: Int = if (operation == 4) 928 else if (operation == 5) 464 else 1856
        val directTuning: Boolean = operation == 2 || operation == 4 || operation == 5
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val settings = StreamSettings.load(context!!)
            check(settings!!.wholeDevice) { "Whole-device mode required" }
            check(!(!settings!!.lowLatency || settings!!.effectiveLatencyMs() != 50)) {
                "The 50 ms low-latency preset is required for comparison"
            }
            check(!(operation == 3 && !settings!!.effectiveDeviceBuffer())) {
                "Device buffer reduction must be enabled for the lease test"
            }
            val input = RootConnector.open(BridgeState.MUSIC, lifetime, settings!!, true)
            descriptor = input.pipe
            if (operation == 3) {
                lease =
                    OwnedAudioPath(
                        OwnedAudioPath.Backend { op: Int ->
                            HalInspection.operateRoute(context!!, input.registration, op)
                        },
                        leaseExecutor,
                        Consumer { newValue: OwnedAudioPath.Update? -> leaseUpdate.set(newValue) },
                        settings.deviceBufferFrames,
                    )
                lease.start()
            }
            val owned: ParcelFileDescriptor? = descriptor
            val close = Runnable {
                try {
                    owned!!.close()
                } catch (ignored: IOException) {}
                RootConnector.close(lifetime)
            }
            synchronized(CaptureProbe::class.java) {
                CaptureProbe.cancellation = close
            }
            timer.schedule(close, 15, TimeUnit.SECONDS)
            val begin = System.nanoTime()
            var nextTiming: Long = begin + TimeUnit.SECONDS.toNanos(2)
            var lastObservation: Long = 0
            var sampleAfter: Long = begin + TimeUnit.SECONDS.toNanos(4)
            var tuned: Boolean = false
            var inspected: Boolean = operation == 3
            AutoCloseInputStream(descriptor).use { stream ->
                try {
                    val pipe = PcmPipe(stream.getChannel(), input.timed)
                    val pcm = ByteBuffer.allocateDirect(settings!!.samplesPerRead() * 2)
                    while (System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(11)) {
                        val count = pipe.read(pcm)
                        if (count < 0) break
                        frames += (count / 4).toLong()
                        val now = System.nanoTime()
                        if (now < nextTiming) continue
                        nextTiming = now + TimeUnit.MILLISECONDS.toNanos(250)
                        val timing = RootConnector.captureTiming(lifetime)
                        timingPolls++
                        if (timing == null) missingTimingPolls++ else lastTiming = timing
                        if (timing != null && timing.size >= 11) zeroSourceFrames = timing[10]
                        if (directTuning && inspected && !tuned) {
                            val report = HalInspection.result()
                            if (report != null) {
                                tuningReport = report
                                check(
                                    report.contains(
                                        "Owned reserve changed: 2587 -> " + targetFrames
                                    )
                                ) {
                                    "Owned tuning was refused"
                                }
                                tuned = true
                                sampleAfter = now + TimeUnit.SECONDS.toNanos(2)
                            }
                        }
                        if (operation == 3 && !tuned) {
                            val update = leaseUpdate.get()
                            if (update != null && update.state === OwnedAudioPath.State.ACTIVE) {
                                tuned = true
                                tuningReport = (update.report)!!
                                sampleAfter = now + TimeUnit.SECONDS.toNanos(2)
                            } else
                                check(
                                    !(update != null &&
                                        update.state !== OwnedAudioPath.State.CHECKING)
                                ) {
                                    "Production lease did not activate"
                                }
                        }
                        if (
                            timing != null &&
                                timing.size >= 3 &&
                                timing[0] == 1L &&
                                timing[1] >= 0 &&
                                now >= timing[2] &&
                                now - timing[2] < TimeUnit.SECONDS.toNanos(3) &&
                                timing[2] >= sampleAfter &&
                                timing[2] != lastObservation
                        ) {
                            lastObservation = timing[2]
                            ages.add(timing[1])
                        }
                        if (!inspected) {
                            inspected = true
                            HalInspection.requestRoute(context, input.registration, tuneOperation)
                        }
                    }
                    check(!(operation >= 2 && !tuned)) { "Tuning not confirmed" }
                } finally {
                    if (lease != null) {
                        lease.close()
                        leaseExecutor.shutdown()
                        leaseExecutor.awaitTermination(2, TimeUnit.SECONDS)
                        restoreReport = lease.restorationReport()
                    }
                    if (directTuning && input.registration != null) {
                        val end: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (HalInspection.result() == null && System.nanoTime() < end) Thread
                            .sleep(10)
                        if (HalInspection.result() != null) {
                            if (HalInspection.result()!!.contains("writeAttempts=0")) {
                                restoreReport = "No restoration needed; writeAttempts=0"
                            } else {
                                HalInspection.requestRoute(
                                    context,
                                    input.registration,
                                    restoreOperation,
                                )
                                while (
                                    HalInspection.result() == null && System.nanoTime() < end
                                ) Thread.sleep(10)
                                restoreReport = HalInspection.result().toString()
                            }
                        } else restoreReport = "Helper pending; closing owned route"
                    }
                }
            }
            check(zeroSourceFrames != 0L) { "Zero source did not run" }
            Collections.sort<Long?>(ages)
            synchronized(CaptureProbe::class.java) {
                CaptureProbe.status =
                    ("Capture-only complete; frames=" +
                        frames +
                        " zeroSourceFrames=" +
                        zeroSourceFrames +
                        " timestampSamples=" +
                        ages.size +
                        " minAgeUs=" +
                        (if (ages.isEmpty()) -1 else ages.get(0)) +
                        " medianAgeUs=" +
                        (if (ages.isEmpty()) -1 else ages.get(ages.size / 2)) +
                        " maxAgeUs=" +
                        (if (ages.isEmpty()) -1 else ages.get(ages.size - 1)) +
                        " ageSamplesUs=" +
                        ages +
                        "; zero-only input; no sender or music playback; HALoperation=" +
                        operation +
                        (if (operation >= 2)
                            " tuning=" + tuningReport + " restoration=" + restoreReport
                        else ""))
            }
        } catch (error: Exception) {
            synchronized(CaptureProbe::class.java) {
                CaptureProbe.status =
                    ("Capture-only unavailable: " +
                        error.javaClass.getSimpleName() +
                        (if (error is IllegalStateException) " " + error.message else "") +
                        "; HALoperation=" +
                        operation +
                        " frames=" +
                        frames +
                        " zeroSourceFrames=" +
                        zeroSourceFrames +
                        " timestampSamples=" +
                        ages.size +
                        " timingPolls=" +
                        timingPolls +
                        " missingTimingPolls=" +
                        missingTimingPolls +
                        " lastTiming=" +
                        lastTiming.contentToString() +
                        " tuning=" +
                        tuningReport +
                        " restoration=" +
                        restoreReport)
            }
        } finally {
            if (descriptor != null) {
                try {
                    descriptor.close()
                } catch (ignored: IOException) {}
            }
            RootConnector.close(lifetime)
            timer.shutdownNow()
            leaseExecutor.shutdownNow()
            synchronized(CaptureProbe::class.java) {
                CaptureProbe.cancellation = null
                CaptureProbe.running = false
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun dump(writer: PrintWriter) {
        writer.println("Capture probe running=" + CaptureProbe.running + " " + CaptureProbe.status)
    }
}
