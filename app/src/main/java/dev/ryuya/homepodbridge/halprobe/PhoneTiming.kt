package dev.ryuya.homepodbridge.halprobe

import kotlin.jvm.JvmRecord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/* Bounded owned-route parameters. Queue measurements are not configuration values. */
@JvmRecord
data class PhoneTiming(val periodMs: Int, val reserveFrames: Int) {
    fun periodFrames(): Int {
        return ((44100 * periodMs / 1000 + 15) / 16) * 16
    }

    fun latencyWindowFrames(): Int {
        return periodFrames() * 2
    }

    fun actualPeriodMs(): Double {
        return periodFrames() * 1000.0 / 44100
    }

    fun reserveMs(): Double {
        return reserveFrames * 1000.0 / 44100
    }

    init {

        require(
            !(periodMs < PhoneTiming.MIN_PERIOD_MS ||
                periodMs > PhoneTiming.MAX_PERIOD_MS ||
                reserveFrames < PhoneTiming.MIN_RESERVE ||
                reserveFrames > PhoneTiming.MAX_RESERVE)
        ) {
            "Unsupported phone timing"
        }
    }

    companion object {
        const val MIN_PERIOD_MS: Int = 1
        const val MAX_PERIOD_MS: Int = 21
        const val MIN_RESERVE: Int = 64
        const val MAX_RESERVE: Int = 800

        @kotlin.jvm.JvmStatic
        fun nearest(actualMs: Double, reserve: Int): PhoneTiming {
            require(!(!java.lang.Double.isFinite(actualMs) || actualMs < 1.09 || actualMs > 21.04))
            var best = PhoneTiming.MIN_PERIOD_MS
            var distance: Double = Double.POSITIVE_INFINITY
            for (ms in PhoneTiming.MIN_PERIOD_MS..PhoneTiming.MAX_PERIOD_MS) {
                val d = abs(PhoneTiming(ms, reserve).actualPeriodMs() - actualMs)
                if (d < distance) {
                    best = ms
                    distance = d
                }
            }
            return PhoneTiming(best, reserve)
        }

        @kotlin.jvm.JvmStatic
        fun fastest(): PhoneTiming {
            return PhoneTiming(1, 64)
        }

        @kotlin.jvm.JvmStatic
        fun fromStored(period: Int, reserve: Int): PhoneTiming {
            return PhoneTiming(
                max(PhoneTiming.MIN_PERIOD_MS, min(PhoneTiming.MAX_PERIOD_MS, period)),
                max(PhoneTiming.MIN_RESERVE, min(PhoneTiming.MAX_RESERVE, reserve)),
            )
        }
    }

    override fun toString(): String =
        "PhoneTiming[periodMs=$periodMs, reserveFrames=$reserveFrames]"
}
