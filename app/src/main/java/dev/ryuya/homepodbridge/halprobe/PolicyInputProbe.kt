package dev.ryuya.homepodbridge.halprobe

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.util.UUID
import kotlin.jvm.JvmStatic
import org.json.JSONArray
import org.json.JSONObject

/* Root self-UID policy only; optional source contains fixed zeros. No AudioRecord construction. */
object PolicyInputProbe {
    @JvmStatic
    @Throws(Exception::class)
    fun run(
        routeOnly: Boolean,
        nonce: UUID?,
        directCapture: Boolean,
        target: Int,
        forwardZero: Boolean,
    ): JSONObject {
        val state = ReadOnlyHalState()
        val baseline = state.snapshot().toString()
        if (Looper.myLooper() == null) Looper.prepare()
        val activity = Class.forName("android.app.ActivityThread")
        val thread = activity.getMethod("systemMain").invoke(null)
        val context: Context? = activity.getMethod("getSystemContext").invoke(thread) as Context?
        val manager = context!!.getSystemService<AudioManager?>(AudioManager::class.java)
        val ruleType = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val rules = Class.forName(ruleType.getName() + "\$Builder").getConstructor().newInstance()
        rules.javaClass
            .getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
            .invoke(
                rules,
                android.media.AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
                1,
            )
        rules.javaClass
            .getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            .invoke(rules, 4, Process.myUid())
        val rule = rules.javaClass.getMethod("build").invoke(rules)
        val mixType = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilder =
            Class.forName(mixType.getName() + "\$Builder")
                .getConstructor(ruleType)
                .newInstance(rule)
        mixBuilder.javaClass
            .getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(mixBuilder, 2)
        mixBuilder.javaClass
            .getMethod("setFormat", AudioFormat::class.java)
            .invoke(
                mixBuilder,
                android.media.AudioFormat.Builder()
                    .setSampleRate(44100)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
        val mix = mixBuilder.javaClass.getMethod("build").invoke(mixBuilder)
        val policyType = Class.forName("android.media.audiopolicy.AudioPolicy")
        val builder =
            Class.forName(policyType.getName() + "\$Builder")
                .getConstructor(Context::class.java)
                .newInstance(context)
        val callbacks = HandlerThread("HomePod-policy-input-probe")
        callbacks.start()
        var policy: Any? = null
        var lease: PolicyInputLease? = null
        var route: PolicyRouteLease? = null
        var registered: Boolean = false
        val result = JSONObject()
        var failure: Exception? = null
        var phase: String = "buildPolicy"
        try {
            builder.javaClass
                .getMethod("setLooper", Looper::class.java)
                .invoke(builder, callbacks.getLooper())
            builder.javaClass.getMethod("addMix", mixType).invoke(builder, mix)
            policy = builder.javaClass.getMethod("build").invoke(builder)
            phase = "registerPolicy"
            val status: Int =
                AudioManager::class
                    .java
                    .getMethod("registerAudioPolicy", policyType)
                    .invoke(manager, policy) as Int
            check(status == 0) { "Self-UID policy registration refused" }
            registered = true
            val address: String? = mixType.getMethod("getRegistration").invoke(mix) as String?
            phase = "openInput"
            if (routeOnly) route = PolicyRouteLease.open(context!!, address!!)
            else lease = PolicyInputLease.open(context!!, address!!)
            result
                .put("inputStarted", !routeOnly)
                .put("virtualOutputConnected", routeOnly)
                .put("recordCreated", false)
                .put("playbackCreated", directCapture)
                .put("zeroSourceOnly", directCapture)
                .put("input", if (lease == null) 0 else lease.input)
            if (directCapture)
                result.put(
                    "directCapture",
                    HalPortProbe.policyCapture(address!!, nonce!!, target, forwardZero),
                )
        } catch (error: Exception) {
            failure = error
        } finally {
            try {
                if (lease != null) lease.close()
                if (route != null) route.close()
            } catch (error: Exception) {
                failure = error
            }
            try {
                if (registered)
                    AudioManager::class
                        .java
                        .getMethod("unregisterAudioPolicy", policyType)
                        .invoke(manager, policy)
            } catch (error: Exception) {
                failure = error
            }
            callbacks.quitSafely()
            callbacks.join(1000)
        }
        var restored: Boolean = false
        for (i in 0..19) {
            restored = baseline == state.snapshot().toString()
            if (restored) break
            Thread.sleep(50)
        }
        result.put("resourcesRestoredExactly", restored)
        if (failure != null) {
            var cause: Throwable? = failure
            while (cause!!.cause != null) cause = cause!!.cause
            result
                .put("phase", phase)
                .put("failure", cause!!.javaClass.getSimpleName() + ": " + cause!!.message)
            val frames = JSONArray()
            for (frame in cause!!.getStackTrace()) {
                if (frames.length() >= 8) break
                frames.put(frame.toString())
            }
            result.put("failureFrames", frames)
        }
        if (!restored) result.put("baseline", JSONObject(baseline)).put("after", state.snapshot())
        return result
    }
}
