package dev.ryuya.homepodbridge

import org.junit.Assert.*
import org.junit.Test

class NowPlayingPositionTest {
    @Test
    fun playbackSpeedAndPauseUseTheSourceClock() {
        assertEquals(13000L, NowPlayingPosition.estimate(10000, 1000, 3000, 1.5f, true, 200000))
        assertEquals(10000L, NowPlayingPosition.estimate(10000, 1000, 3000, 1.5f, false, 200000))
        assertEquals(10000L, NowPlayingPosition.estimate(10000, 0, 3000, 1f, true, 200000))
    }

    @Test
    fun unknownInvalidAndSeekPastEndRemainBounded() {
        assertEquals(-1L, NowPlayingPosition.estimate(-1, 1000, 3000, 1f, true, 200000))
        assertEquals(15000L, NowPlayingPosition.estimate(10000, 1000, 30000, 1f, true, 15000))
        assertEquals(10000L, NowPlayingPosition.estimate(10000, 4000, 3000, 1f, true, -1))
        assertEquals(10000L, NowPlayingPosition.estimate(10000, 1000, 3000, Float.NaN, true, -1))
    }
}
