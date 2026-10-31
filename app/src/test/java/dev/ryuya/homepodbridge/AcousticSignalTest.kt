package dev.ryuya.homepodbridge

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class AcousticSignalTest {
    @Test
    fun fixedChirpsHaveQuietBoundedAmplitudeAndSilentEdges() {
        for (tone in 0 until AcousticSignal.COUNT) {
            var energy: Long = 0
            assertEquals(0, AcousticSignal.sample(tone, -1).toInt())
            assertEquals(0, AcousticSignal.sample(tone, 0).toInt())
            assertEquals(0, AcousticSignal.sample(tone, AcousticSignal.TONE_FRAMES - 1).toInt())
            assertEquals(0, AcousticSignal.sample(tone, AcousticSignal.TONE_FRAMES).toInt())
            for (i in 0 until AcousticSignal.TONE_FRAMES) {
                val s = AcousticSignal.sample(tone, i).toInt()
                assertTrue(abs(s) <= 3933)
                energy += s.toLong() * s
            }
            assertTrue(energy > 1000000)
        }
        assertEquals(0, AcousticSignal.sample(-1, 100).toInt())
        assertEquals(0, AcousticSignal.sample(3, 100).toInt())
    }

    @Test
    fun distinctPatternsAndSpacingAvoidAmbiguousSingleToneMatches() {
        for (tone in 1..2) {
            assertTrue(
                AcousticSignal.firstFrame(tone) - AcousticSignal.firstFrame(tone - 1) >
                    AcousticSignal.TONE_FRAMES * 5
            )
            var dot: Long = 0
            var aa: Long = 0
            var bb: Long = 0
            for (i in 0 until AcousticSignal.TONE_FRAMES) {
                val a = AcousticSignal.sample(0, i).toLong()
                val b = AcousticSignal.sample(tone, i).toLong()
                dot += a * b
                aa += a * a
                bb += b * b
            }
            assertTrue(abs(dot) / sqrt(aa.toDouble() * bb) < 0.5)
        }
    }
}
