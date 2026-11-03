package dev.ryuya.homepodbridge.halprobe

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Binder
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import dev.ryuya.homepodbridge.compat.DexSymbols
import dev.ryuya.homepodbridge.compat.compatibleMethod
import dev.ryuya.homepodbridge.compat.compatibleName
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
        policyType = DexSymbols.findClass("android.media.audiopolicy.AudioPolicy")
        callbacks.start()
        try {
            val ruleType = DexSymbols.findClass("android.media.audiopolicy.AudioMixingRule")
            val rules =
                DexSymbols.findClass(ruleType.compatibleName() + "\$Builder")
                    .getConstructor()
                    .newInstance()
            if (scope.whole) {
                rules.javaClass
                    .compatibleMethod("setTargetMixRole", Int::class.javaPrimitiveType)
                    .invoke(rules, 0)
                rules.javaClass
                    .compatibleMethod(
                        "excludeMixRule",
                        Int::class.javaPrimitiveType,
                        Any::class.java,
                    )
                    .invoke(rules, 4, Integer.valueOf(scope.excludedUid))
                for (usage in scope.excludedUsages()!!) rules.javaClass
                    .compatibleMethod(
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
                    .compatibleMethod(
                        "addRule",
                        AudioAttributes::class.java,
                        Int::class.javaPrimitiveType,
                    )
                    .invoke(
                        rules,
                        android.media.AudioAttributes.Builder()
                            .setUsage(scope.matchedUsage())
                            .build(),
                        1,
                    )
                rules.javaClass
                    .compatibleMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
                    .invoke(rules, 4, Integer.valueOf(scope.selectedUid))
            }
            val mixType = DexSymbols.findClass("android.media.audiopolicy.AudioMix")
            val mix =
                DexSymbols.findClass(mixType.compatibleName() + "\$Builder")
                    .getConstructor(ruleType)
                    .newInstance(rules.javaClass.compatibleMethod("build").invoke(rules))
            mix.javaClass
                .compatibleMethod("setRouteFlags", Int::class.javaPrimitiveType)
                .invoke(mix, 2)
            mix.javaClass
                .compatibleMethod("setFormat", AudioFormat::class.java)
                .invoke(
                    mix,
                    android.media.AudioFormat.Builder()
                        .setSampleRate(44100)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
            val audioMix = mix.javaClass.compatibleMethod("build").invoke(mix)
            val builder =
                DexSymbols.findClass(policyType.compatibleName() + "\$Builder")
                    .getConstructor(Context::class.java)
                    .newInstance(context)
            builder.javaClass
                .compatibleMethod("setLooper", Looper::class.java)
                .invoke(builder, callbacks.getLooper())
            builder.javaClass.compatibleMethod("addMix", mixType).invoke(builder, audioMix)
            policy = builder.javaClass.compatibleMethod("build").invoke(builder)
            val status: Int =
                AudioManager::class
                    .java
                    .compatibleMethod("registerAudioPolicy", policyType)
                    .invoke(manager, policy) as Int
            check(status == 0) { "Playback policy refused" }
            address = (mixType.compatibleMethod("getRegistration").invoke(audioMix) as String?)!!
            before.ready(address)
            route = PolicyRouteLease.open(context, address)
            // Match AudioRecord's fixed-volume submix lease without changing the phone/receiver
            // volume.
            val binder: IBinder? =
                DexSymbols.findClass("android.os.ServiceManager")
                    .compatibleMethod("getService", String::class.java)
                    .invoke(null, Context.AUDIO_SERVICE) as IBinder?
            audioServiceType = DexSymbols.findClass("android.media.IAudioService")
            audioService =
                DexSymbols.findClass("android.media.IAudioService\$Stub")
                    .compatibleMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder)
            volumeRequested = true
            audioServiceType!!
                .compatibleMethod(
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
                        .compatibleMethod(
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
                        .compatibleMethod("unregisterAudioPolicy", policyType)
                        .invoke(manager, policy)
            } finally {
                policy = null
                callbacks.quitSafely()
                callbacks.join(1000)
            }
        }
    }
}
