package dev.ryuya.homepodbridge.halprobe

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Binder
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import kotlin.jvm.JvmField
import kotlin.jvm.JvmOverloads

/* Owns one playback policy and its exact virtual output registration. */
class RegisteredPolicy
@JvmOverloads
constructor(
    context: Context,
    scope: CaptureScope,
    before: RegisteredPolicy.BeforeRoute = RegisteredPolicy.BeforeRoute { address: String? -> },
) : AutoCloseable {
    private val manager: AudioManager
    private val policyType: Class<*>
    private val callbacks: HandlerThread = HandlerThread("HomePod-HAL-policy")
    private var policy: Any? = null
    private var route: PolicyRouteLease? = null
    private val volumeToken: IBinder = Binder()
    private var audioService: Any? = null
    private var audioServiceType: Class<*>? = null
    private var volumeRequested: Boolean = false
    @JvmField val address: String

    fun interface BeforeRoute {
        @Throws(Exception::class) fun ready(address: String?)
    }

    init {
        manager = context.getSystemService<AudioManager?>(AudioManager::class.java)
        policyType = Class.forName("android.media.audiopolicy.AudioPolicy")
        callbacks.start()
        try {
            val ruleType = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val rules =
                Class.forName(ruleType.getName() + "\$Builder").getConstructor().newInstance()
            if (scope.whole) {
                rules.javaClass
                    .getMethod("setTargetMixRole", Int::class.javaPrimitiveType)
                    .invoke(rules, 0)
                rules.javaClass
                    .getMethod("excludeMixRule", Int::class.javaPrimitiveType, Any::class.java)
                    .invoke(rules, 4, Integer.valueOf(scope.excludedUid))
                for (usage in scope.excludedUsages()!!) rules.javaClass
                    .getMethod(
                        "excludeRule",
                        AudioAttributes::class.java,
                        Int::class.javaPrimitiveType,
                    )
                    .invoke(
                        rules,
                        android.media.AudioAttributes.Builder().setUsage(usage).build(),
                        1,
                    )
            } else {
                rules.javaClass
                    .getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
                    .invoke(
                        rules,
                        android.media.AudioAttributes.Builder()
                            .setUsage(scope.matchedUsage())
                            .build(),
                        1,
                    )
                rules.javaClass
                    .getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
                    .invoke(rules, 4, Integer.valueOf(scope.selectedUid))
            }
            val mixType = Class.forName("android.media.audiopolicy.AudioMix")
            val mix =
                Class.forName(mixType.getName() + "\$Builder")
                    .getConstructor(ruleType)
                    .newInstance(rules.javaClass.getMethod("build").invoke(rules))
            mix.javaClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mix, 2)
            mix.javaClass
                .getMethod("setFormat", AudioFormat::class.java)
                .invoke(
                    mix,
                    android.media.AudioFormat.Builder()
                        .setSampleRate(44100)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
            val audioMix = mix.javaClass.getMethod("build").invoke(mix)
            val builder =
                Class.forName(policyType.getName() + "\$Builder")
                    .getConstructor(Context::class.java)
                    .newInstance(context)
            builder.javaClass
                .getMethod("setLooper", Looper::class.java)
                .invoke(builder, callbacks.getLooper())
            builder.javaClass.getMethod("addMix", mixType).invoke(builder, audioMix)
            policy = builder.javaClass.getMethod("build").invoke(builder)
            val status: Int =
                AudioManager::class
                    .java
                    .getMethod("registerAudioPolicy", policyType)
                    .invoke(manager, policy) as Int
            check(status == 0) { "Playback policy refused" }
            address = (mixType.getMethod("getRegistration").invoke(audioMix) as String?)!!
            before.ready(address)
            route = PolicyRouteLease.open(context, address)
            // Match AudioRecord's fixed-volume submix lease without changing the phone/receiver
            // volume.
            val binder: IBinder? =
                Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, Context.AUDIO_SERVICE) as IBinder?
            audioServiceType = Class.forName("android.media.IAudioService")
            audioService =
                Class.forName("android.media.IAudioService\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder)
            volumeRequested = true
            audioServiceType!!
                .getMethod(
                    "forceRemoteSubmixFullVolume",
                    Boolean::class.javaPrimitiveType,
                    IBinder::class.java,
                )
                .invoke(audioService, true, volumeToken)
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    @Throws(Exception::class)
    override fun close() {
        try {
            try {
                if (volumeRequested)
                    audioServiceType!!
                        .getMethod(
                            "forceRemoteSubmixFullVolume",
                            Boolean::class.javaPrimitiveType,
                            IBinder::class.java,
                        )
                        .invoke(audioService, false, volumeToken)
            } finally {
                volumeRequested = false
                if (route != null) route!!.close()
            }
        } finally {
            try {
                if (policy != null)
                    AudioManager::class
                        .java
                        .getMethod("unregisterAudioPolicy", policyType)
                        .invoke(manager, policy)
            } finally {
                policy = null
                callbacks.quitSafely()
                callbacks.join(1000)
            }
        }
    }
}
