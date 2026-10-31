package dev.ryuya.homepodbridge

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/* Fixed quiet chirps, written only into sender PCM, never into a phone playback AudioTrack. */
object AcousticSignal {
    const val RATE: Int = 44100
    const val COUNT: Int = 3
    const val TONE_FRAMES: Int = 3528
    const val FIRST_FRAME: Int = 88200
    const val GAP_FRAMES: Int = 26460
    const val PEAK: Double = 0.12
    @JvmField val PHASE_CODE: IntArray? = intArrayOf(0xACE1, 0xB4D3, 0x69A7)
    @JvmField val LOW: IntArray? = intArrayOf(1600, 2100, 1300)
    @JvmField val HIGH: IntArray? = intArrayOf(3900, 4700, 3400)

    @JvmStatic
    fun firstFrame(index: Int): Long {
        return AcousticSignal.FIRST_FRAME + index.toLong() * AcousticSignal.GAP_FRAMES
    }

    @JvmStatic
    fun sample(index: Int, frame: Int): Short {
        if (
            index < 0 ||
                index >= AcousticSignal.COUNT ||
                frame < 0 ||
                frame >= AcousticSignal.TONE_FRAMES
        )
            return 0
        return AcousticSignal.sample(
            AcousticSignal.LOW!![index],
            AcousticSignal.HIGH!![index],
            AcousticSignal.PHASE_CODE!![index],
            frame,
        )
    }

    @JvmStatic
    fun sample(low: Int, high: Int, code: Int, frame: Int): Short {
        if (frame < 0 || frame >= AcousticSignal.TONE_FRAMES) return 0
        val t: Double = frame / AcousticSignal.RATE.toDouble()
        val seconds: Double = AcousticSignal.TONE_FRAMES / AcousticSignal.RATE.toDouble()
        val phase: Double = 2 * Math.PI * (low * t + 0.5 * (high - low) * t * t / seconds)
        val window: Double = 0.5 - 0.5 * cos(2 * Math.PI * frame / (AcousticSignal.TONE_FRAMES - 1))
        val sign: Int =
            if (((code shr (frame * 16 / AcousticSignal.TONE_FRAMES)) and 1) == 0) -1 else 1
        return Math.round(32767 * AcousticSignal.PEAK * window * sin(phase) * sign).toShort()
    }
}
