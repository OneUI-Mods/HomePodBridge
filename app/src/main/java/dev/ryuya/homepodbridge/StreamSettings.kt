package dev.ryuya.homepodbridge

import android.content.Context
import android.content.SharedPreferences
import dev.ryuya.homepodbridge.halprobe.PhoneTiming
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.math.min

class StreamSettings
@JvmOverloads
constructor(
    wholeDevice: Boolean,
    bufferMs: Int,
    blockMs: Int,
    latencyMs: Int,
    lowLatency: Boolean = false,
    highQuality: Boolean = false,
    compressedAudio: Boolean = false,
    adaptiveBuffer: Boolean = true,
    deviceBuffer: Boolean = false,
    deviceBufferFrames: Int = 1856,
    dedicatedCapture: Boolean = false,
    receiverTiming: ReceiverTiming? =
        ReceiverTiming.linked(if (lowLatency) 50 else if (highQuality) 1500 else latencyMs),
    shortAudioPackets: Boolean = false,
    shortMixerPeriod: Boolean = false,
    manualMode: Boolean = false,
    phoneTiming: PhoneTiming? = PhoneTiming.fastest(),
    shortPacketFrames: Int = 88,
) {
    val wholeDevice: Boolean
    val bufferMs: Int
    val blockMs: Int
    val latencyMs: Int
    val lowLatency: Boolean
    val highQuality: Boolean
    val compressedAudio: Boolean
    val adaptiveBuffer: Boolean
    val deviceBuffer: Boolean
    val deviceBufferFrames: Int
    val dedicatedCapture: Boolean
    val receiverTiming: ReceiverTiming?
    val shortAudioPackets: Boolean
    val shortMixerPeriod: Boolean
    val manualMode: Boolean
    val phoneTiming: PhoneTiming
    val shortPacketFrames: Int

    fun save(context: Context) {
        saveTo(context.getSharedPreferences("stream", Context.MODE_PRIVATE))
        if (manualMode) saveTo(context.getSharedPreferences("manualStream", Context.MODE_PRIVATE))
    }

    private fun saveTo(preferences: SharedPreferences) {
        preferences
            .edit()
            .putBoolean("wholeDevice", wholeDevice)
            .putInt("bufferMs", bufferMs)
            .putInt("blockMs", blockMs)
            .putInt("latencyMs", latencyMs)
            .putBoolean("lowLatency", lowLatency)
            .putBoolean("highQuality", highQuality)
            .putBoolean("compressedAudio", compressedAudio)
            .putBoolean("adaptiveBuffer", adaptiveBuffer)
            .putBoolean("deviceBuffer", deviceBuffer)
            .putBoolean("dedicatedCapture", dedicatedCapture)
            .putBoolean("shortAudioPackets", shortAudioPackets)
            .putInt("shortPacketFrames", shortPacketFrames)
            .putBoolean("shortMixerPeriod", shortMixerPeriod)
            .putBoolean("manualMode", manualMode)
            .putInt("phonePeriodMs", phoneTiming!!.periodMs)
            .putInt("phoneReserveFrames", phoneTiming!!.reserveFrames)
            .putBoolean("independentReceiverTiming", receiverTiming!!.independent)
            .putInt("receiverMinimumMs", receiverTiming!!.minimumMs)
            .putInt("receiverMaximumMs", receiverTiming!!.maximumMs)
            .putInt("presentationOffsetMs", receiverTiming!!.presentationMs)
            .putInt("deviceBufferFrames", deviceBufferFrames)
            .apply()
    }

    fun withWholeDevice(enabled: Boolean): StreamSettings {
        return StreamSettings(
            enabled,
            bufferMs,
            blockMs,
            latencyMs,
            lowLatency,
            highQuality,
            compressedAudio,
            adaptiveBuffer,
            deviceBuffer,
            deviceBufferFrames,
            dedicatedCapture,
            receiverTiming,
            shortAudioPackets,
            shortMixerPeriod,
            manualMode,
            phoneTiming,
            shortPacketFrames,
        )
    }

    fun withProfile(profile: Int): StreamSettings {
        return StreamSettings(
            wholeDevice,
            bufferMs,
            blockMs,
            latencyMs,
            profile == 1,
            profile == 2,
            compressedAudio,
            adaptiveBuffer,
            deviceBuffer,
            deviceBufferFrames,
            dedicatedCapture,
            receiverTiming,
            shortAudioPackets,
            shortMixerPeriod,
            profile == 3,
            phoneTiming,
            shortPacketFrames,
        )
    }

    fun withCompression(enabled: Boolean): StreamSettings {
        return StreamSettings(
            wholeDevice,
            bufferMs,
            blockMs,
            latencyMs,
            lowLatency,
            highQuality,
            enabled,
            adaptiveBuffer,
            deviceBuffer,
            deviceBufferFrames,
            dedicatedCapture,
            receiverTiming,
            shortAudioPackets,
            shortMixerPeriod,
            manualMode,
            phoneTiming,
            shortPacketFrames,
        )
    }

    fun withBuffers(buffer: Int, block: Int, latency: Int): StreamSettings {
        return StreamSettings(
            wholeDevice,
            buffer,
            block,
            latency,
            false,
            false,
            compressedAudio,
            adaptiveBuffer,
            deviceBuffer,
            deviceBufferFrames,
            dedicatedCapture,
            receiverTiming,
            shortAudioPackets,
            shortMixerPeriod,
            manualMode,
            phoneTiming,
            shortPacketFrames,
        )
    }

    fun withAdaptiveBuffer(enabled: Boolean): StreamSettings {
        return StreamSettings(
            wholeDevice,
            bufferMs,
            blockMs,
            latencyMs,
            lowLatency,
            highQuality,
            compressedAudio,
            enabled,
            deviceBuffer,
            deviceBufferFrames,
            dedicatedCapture,
            receiverTiming,
            shortAudioPackets,
            shortMixerPeriod,
            manualMode,
            phoneTiming,
            shortPacketFrames,
        )
    }

    fun withDeviceBuffer(enabled: Boolean): StreamSettings {
        return StreamSettings(
            wholeDevice,
            bufferMs,
            blockMs,
            latencyMs,
            lowLatency,
            highQuality,
            compressedAudio,
            adaptiveBuffer,
            enabled,
            deviceBufferFrames,
            dedicatedCapture,
            receiverTiming,
            shortAudioPackets,
            shortMixerPeriod,
            manualMode,
            phoneTiming,
            shortPacketFrames,
        )
    }

    fun withDeviceBufferFrames(frames: Int): StreamSettings {
        return StreamSettings(
            wholeDevice,
            bufferMs,
            blockMs,
            latencyMs,
            lowLatency,
            highQuality,
            compressedAudio,
            adaptiveBuffer,
            deviceBuffer,
            frames,
            dedicatedCapture,
            receiverTiming,
            shortAudioPackets,
            shortMixerPeriod,
            manualMode,
            phoneTiming,
            shortPacketFrames,
        )
    }

    fun withDedicatedCapture(enabled: Boolean): StreamSettings {
        return StreamSettings(
            wholeDevice,
            bufferMs,
            blockMs,
            latencyMs,
            lowLatency,
            highQuality,
            compressedAudio,
            adaptiveBuffer,
            deviceBuffer,
            deviceBufferFrames,
            enabled,
            receiverTiming,
            shortAudioPackets,
            shortMixerPeriod,
            manualMode,
            phoneTiming,
            shortPacketFrames,
        )
    }

    fun withReceiverTiming(timing: ReceiverTiming?): StreamSettings {
        return StreamSettings(
            wholeDevice,
            bufferMs,
            blockMs,
            latencyMs,
            lowLatency,
            highQuality,
            compressedAudio,
            adaptiveBuffer,
            deviceBuffer,
            deviceBufferFrames,
            dedicatedCapture,
            timing,
            shortAudioPackets,
            shortMixerPeriod,
            manualMode,
            phoneTiming,
            shortPacketFrames,
        )
    }

    fun withShortAudioPackets(enabled: Boolean): StreamSettings {
        return StreamSettings(
            wholeDevice,
            bufferMs,
            blockMs,
            latencyMs,
            lowLatency,
            highQuality,
            compressedAudio,
            adaptiveBuffer,
            deviceBuffer,
            deviceBufferFrames,
            dedicatedCapture,
            receiverTiming,
            enabled,
            shortMixerPeriod,
            manualMode,
            phoneTiming,
            shortPacketFrames,
        )
    }

    fun withShortMixerPeriod(enabled: Boolean): StreamSettings {
        return StreamSettings(
            wholeDevice,
            bufferMs,
            blockMs,
            latencyMs,
            lowLatency,
            highQuality,
            compressedAudio,
            adaptiveBuffer,
            deviceBuffer,
            deviceBufferFrames,
            dedicatedCapture,
            receiverTiming,
            shortAudioPackets,
            enabled,
            manualMode,
            phoneTiming,
            shortPacketFrames,
        )
    }

    fun withManualMode(enabled: Boolean): StreamSettings {
        return StreamSettings(
            wholeDevice,
            bufferMs,
            blockMs,
            latencyMs,
            if (enabled) false else lowLatency,
            if (enabled) false else highQuality,
            compressedAudio,
            adaptiveBuffer,
            deviceBuffer,
            deviceBufferFrames,
            dedicatedCapture,
            receiverTiming,
            shortAudioPackets,
            shortMixerPeriod,
            enabled,
            phoneTiming,
            shortPacketFrames,
        )
    }

    fun withPhoneTiming(timing: PhoneTiming?): StreamSettings {
        return StreamSettings(
            wholeDevice,
            bufferMs,
            blockMs,
            latencyMs,
            lowLatency,
            highQuality,
            compressedAudio,
            adaptiveBuffer,
            deviceBuffer,
            deviceBufferFrames,
            dedicatedCapture,
            receiverTiming,
            shortAudioPackets,
            shortMixerPeriod,
            manualMode,
            timing,
            shortPacketFrames,
        )
    }

    fun effectivePhoneTiming(): PhoneTiming {
        return (if (manualMode) phoneTiming else PhoneTiming.fastest())!!
    }

    fun asManual(): StreamSettings {
        return StreamSettings(
            wholeDevice,
            effectiveBufferMs(),
            effectiveBlockMs(),
            requestedPresentationMs(),
            false,
            false,
            compressedAudio,
            false,
            deviceBuffer,
            deviceBufferFrames,
            effectiveDedicatedCapture(),
            ReceiverTiming(
                true,
                requestedReceiverMinimumMs(),
                requestedReceiverMaximumMs(),
                requestedPresentationMs(),
            ),
            effectiveShortAudioPackets(),
            effectiveShortMixerPeriod(),
            true,
            if (effectiveShortMixerPeriod()) effectivePhoneTiming() else PhoneTiming(21, 800),
            shortPacketFrames,
        )
    }

    fun preset(selected: Int): StreamSettings {
        if (selected == 3) return asManual()
        require(!(selected < 0 || selected > 2))
        var base = withProfile(selected)
        if (selected == 0) base = base!!.withBuffers(150, 10, 1500)
        return base!!
            .withAdaptiveBuffer(false)!!
            .withCompression(false)!!
            .withDedicatedCapture(selected == 1)!!
            .withShortMixerPeriod(selected == 1)!!
            .withShortAudioPackets(false)!!
            .withReceiverTiming(ReceiverTiming.linked(if (selected == 1) 50 else 1500))
    }

    fun effectiveShortMixerPeriod(): Boolean {
        return effectiveDedicatedCapture() && (manualMode || shortMixerPeriod)
    }

    fun effectiveShortAudioPackets(): Boolean {
        return effectiveDedicatedCapture() && shortAudioPackets
    }

    fun withShortPacketFrames(frames: Int): StreamSettings {
        return StreamSettings(
            wholeDevice,
            bufferMs,
            blockMs,
            latencyMs,
            lowLatency,
            highQuality,
            compressedAudio,
            adaptiveBuffer,
            deviceBuffer,
            deviceBufferFrames,
            dedicatedCapture,
            receiverTiming,
            shortAudioPackets,
            shortMixerPeriod,
            manualMode,
            phoneTiming,
            frames,
        )
    }

    fun requestedPacketFrames(): Int {
        return packetFramesForBackend(effectiveDedicatedCapture())
    }

    fun packetFramesForBackend(dedicatedActive: Boolean): Int {
        return if (dedicatedActive && effectiveShortAudioPackets()) shortPacketFrames else 352
    }

    fun requestedReceiverMinimumMs(): Int {
        return if (receiverTiming!!.independent) receiverTiming!!.minimumMs
        else min(effectiveLatencyMs(), 250)
    }

    fun requestedReceiverMaximumMs(): Int {
        return if (receiverTiming!!.independent) receiverTiming!!.maximumMs
        else effectiveLatencyMs()
    }

    fun requestedPresentationMs(): Int {
        return if (receiverTiming!!.independent) receiverTiming!!.presentationMs
        else effectiveLatencyMs()
    }

    fun effectiveDedicatedCapture(): Boolean {
        return (lowLatency || manualMode) && dedicatedCapture
    }

    fun effectiveDeviceBuffer(): Boolean {
        return (lowLatency || manualMode) && deviceBuffer
    }

    fun effectiveAdaptiveBuffer(): Boolean {
        return !manualMode && lowLatency && adaptiveBuffer
    }

    fun profile(): Int {
        return if (manualMode) 3 else if (lowLatency) 1 else if (highQuality) 2 else 0
    }

    fun samplesPerRead(): Int {
        return 2 * (44100 * effectiveBlockMs() / 1000)
    }

    fun captureUnitMs(): Double {
        return if (effectiveDedicatedCapture()) BufferLimits.DEDICATED_FRAMES * 1000.0 / 44100
        else effectiveBlockMs().toDouble()
    }

    fun effectiveBufferMs(): Int {
        return if (lowLatency) (if (adaptiveBuffer) 8 else 0)
        else if (highQuality) 150 else bufferMs
    }

    fun effectiveBlockMs(): Int {
        return if (lowLatency) 2 else if (highQuality) 10 else blockMs
    }

    fun effectiveLatencyMs(): Int {
        return if (lowLatency) 50 else if (highQuality) 1500 else latencyMs
    }

    init {

        var bufferMs: Int = bufferMs
        var blockMs: Int = blockMs
        var latencyMs: Int = latencyMs
        var highQuality: Boolean = highQuality
        var deviceBufferFrames: Int = deviceBufferFrames
        var receiverTiming: ReceiverTiming? = receiverTiming
        var manualMode: Boolean = manualMode
        var phoneTiming: PhoneTiming? = phoneTiming
        require(!(shortPacketFrames != 44 && shortPacketFrames != 88)) { "Unsupported packet size" }
        bufferMs =
            BufferLimits.clamp(bufferMs, BufferLimits.RESERVE_MIN_MS, BufferLimits.RESERVE_MAX_MS)
        blockMs =
            BufferLimits.clamp(blockMs, BufferLimits.CAPTURE_MIN_MS, BufferLimits.CAPTURE_MAX_MS)
        latencyMs =
            BufferLimits.clamp(latencyMs, BufferLimits.TIMING_MIN_MS, BufferLimits.TIMING_MAX_MS)
        deviceBufferFrames = if (deviceBufferFrames == 928) 928 else 1856
        if (lowLatency) highQuality = false
        if (lowLatency || highQuality) manualMode = false
        if (phoneTiming == null) phoneTiming = PhoneTiming.fastest()
        if (receiverTiming == null) receiverTiming = ReceiverTiming.linked(latencyMs)

        this.wholeDevice = wholeDevice
        this.bufferMs = bufferMs
        this.blockMs = blockMs
        this.latencyMs = latencyMs
        this.lowLatency = lowLatency
        this.highQuality = highQuality
        this.compressedAudio = compressedAudio
        this.adaptiveBuffer = adaptiveBuffer
        this.deviceBuffer = deviceBuffer
        this.deviceBufferFrames = deviceBufferFrames
        this.dedicatedCapture = dedicatedCapture
        this.receiverTiming = receiverTiming
        this.shortAudioPackets = shortAudioPackets
        this.shortMixerPeriod = shortMixerPeriod
        this.manualMode = manualMode
        this.phoneTiming = phoneTiming
        this.shortPacketFrames = shortPacketFrames
    }

    companion object {
        @JvmStatic
        fun load(context: Context): StreamSettings {
            return StreamSettings.read(context.getSharedPreferences("stream", Context.MODE_PRIVATE))
        }

        @JvmStatic
        private fun read(preferences: SharedPreferences): StreamSettings {
            val saved =
                StreamSettings(
                    preferences.getBoolean("wholeDevice", false),
                    preferences.getInt("bufferMs", 150),
                    preferences.getInt("blockMs", 10),
                    preferences.getInt("latencyMs", 1500),
                    preferences.getBoolean("lowLatency", false),
                    preferences.getBoolean("highQuality", false),
                    preferences.getBoolean("compressedAudio", false),
                    preferences.getBoolean("adaptiveBuffer", true),
                    preferences.getBoolean("deviceBuffer", false),
                    preferences.getInt("deviceBufferFrames", 1856),
                    preferences.getBoolean("dedicatedCapture", false),
                )
            val target = saved.effectiveLatencyMs()
            return saved
                .withReceiverTiming(
                    ReceiverTiming(
                        preferences.getBoolean("independentReceiverTiming", false),
                        preferences.getInt("receiverMinimumMs", min(target, 250)),
                        preferences.getInt("receiverMaximumMs", target),
                        preferences.getInt("presentationOffsetMs", target),
                    )
                )!!
                .withShortAudioPackets(preferences.getBoolean("shortAudioPackets", false))!!
                .withShortPacketFrames(
                    if (preferences.getInt("shortPacketFrames", 88) == 44) 44 else 88
                )!!
                .withShortMixerPeriod(preferences.getBoolean("shortMixerPeriod", false))!!
                .withManualMode(preferences.getBoolean("manualMode", false))!!
                .withPhoneTiming(
                    PhoneTiming.fromStored(
                        preferences.getInt("phonePeriodMs", 2),
                        preferences.getInt("phoneReserveFrames", 96),
                    )
                )
        }

        @JvmStatic
        fun savedManual(context: Context): StreamSettings {
            val current = StreamSettings.load(context)
            val saved = context.getSharedPreferences("manualStream", Context.MODE_PRIVATE)
            return (if (saved.contains("manualMode"))
                    StreamSettings.read(saved)!!.withManualMode(true)
                else current!!.asManual())!!
                .withWholeDevice(current!!.wholeDevice)
        }
    }

    fun wholeDevice(): Boolean = wholeDevice

    fun bufferMs(): Int = bufferMs

    fun blockMs(): Int = blockMs

    fun latencyMs(): Int = latencyMs

    fun lowLatency(): Boolean = lowLatency

    fun highQuality(): Boolean = highQuality

    fun compressedAudio(): Boolean = compressedAudio

    fun adaptiveBuffer(): Boolean = adaptiveBuffer

    fun deviceBuffer(): Boolean = deviceBuffer

    fun deviceBufferFrames(): Int = deviceBufferFrames

    fun dedicatedCapture(): Boolean = dedicatedCapture

    fun receiverTiming(): ReceiverTiming? = receiverTiming

    fun shortAudioPackets(): Boolean = shortAudioPackets

    fun shortMixerPeriod(): Boolean = shortMixerPeriod

    fun manualMode(): Boolean = manualMode

    fun phoneTiming(): PhoneTiming = phoneTiming!!

    fun shortPacketFrames(): Int = shortPacketFrames

    override fun equals(other: Any?): Boolean =
        other is StreamSettings &&
            wholeDevice == other.wholeDevice &&
            bufferMs == other.bufferMs &&
            blockMs == other.blockMs &&
            latencyMs == other.latencyMs &&
            lowLatency == other.lowLatency &&
            highQuality == other.highQuality &&
            compressedAudio == other.compressedAudio &&
            adaptiveBuffer == other.adaptiveBuffer &&
            deviceBuffer == other.deviceBuffer &&
            deviceBufferFrames == other.deviceBufferFrames &&
            dedicatedCapture == other.dedicatedCapture &&
            receiverTiming == other.receiverTiming &&
            shortAudioPackets == other.shortAudioPackets &&
            shortMixerPeriod == other.shortMixerPeriod &&
            manualMode == other.manualMode &&
            phoneTiming == other.phoneTiming &&
            shortPacketFrames == other.shortPacketFrames

    override fun hashCode(): Int {
        var result = wholeDevice.hashCode()
        result = 31 * result + bufferMs.hashCode()
        result = 31 * result + blockMs.hashCode()
        result = 31 * result + latencyMs.hashCode()
        result = 31 * result + lowLatency.hashCode()
        result = 31 * result + highQuality.hashCode()
        result = 31 * result + compressedAudio.hashCode()
        result = 31 * result + adaptiveBuffer.hashCode()
        result = 31 * result + deviceBuffer.hashCode()
        result = 31 * result + deviceBufferFrames.hashCode()
        result = 31 * result + dedicatedCapture.hashCode()
        result = 31 * result + receiverTiming.hashCode()
        result = 31 * result + shortAudioPackets.hashCode()
        result = 31 * result + shortMixerPeriod.hashCode()
        result = 31 * result + manualMode.hashCode()
        result = 31 * result + phoneTiming.hashCode()
        result = 31 * result + shortPacketFrames.hashCode()
        return result
    }

    override fun toString(): String =
        "StreamSettings[wholeDevice=$wholeDevice, bufferMs=$bufferMs, blockMs=$blockMs, latencyMs=$latencyMs, lowLatency=$lowLatency, highQuality=$highQuality, compressedAudio=$compressedAudio, adaptiveBuffer=$adaptiveBuffer, deviceBuffer=$deviceBuffer, deviceBufferFrames=$deviceBufferFrames, dedicatedCapture=$dedicatedCapture, receiverTiming=$receiverTiming, shortAudioPackets=$shortAudioPackets, shortMixerPeriod=$shortMixerPeriod, manualMode=$manualMode, phoneTiming=$phoneTiming, shortPacketFrames=$shortPacketFrames]"
}
