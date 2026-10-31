package dev.ryuya.homepodbridge

import org.junit.Assert.*
import org.junit.Test

class VolumePolicyTest {
    @Test
    fun explicitMaximumIsHonoured() {
        assertEquals(0, VolumePolicy.clamp(-1, 5))
        assertEquals(0, VolumePolicy.clamp(0, 5))
        assertEquals(5, VolumePolicy.clamp(100, 5))
        assertEquals(5, VolumePolicy.clamp(Int.MAX_VALUE, 5))
    }

    @Test
    fun applicationSupportsTheFullRange() {
        assertEquals(0, VolumePolicy.apply(-1))
        assertEquals(0, VolumePolicy.apply(0))
        assertEquals(5, VolumePolicy.apply(5))
        assertEquals(50, VolumePolicy.apply(50))
        assertEquals(100, VolumePolicy.apply(100))
        assertEquals(100, VolumePolicy.apply(Int.MAX_VALUE))
    }
}
