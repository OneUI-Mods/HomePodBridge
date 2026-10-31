package dev.ryuya.homepodbridge

import org.junit.Assert.*
import org.junit.Test

class RemoteVolumeIconsTest {
    @Test
    fun onlyOwnedDynamicRowTogglesAndRestoresItsVolume() {
        val icons = RemoteVolumeIcons()
        assertNull(icons.target(100, 20))
        icons.stream = 103
        assertNull(icons.target(3, 12))
        assertNull(icons.target(104, 90))
        assertEquals(0, icons.target(103, 2))
        assertEquals(2, icons.target(103, 0))
        assertEquals(0, icons.target(103, 20))
        assertEquals(20, icons.target(103, 0))
        val nextConnection = RemoteVolumeIcons()
        nextConnection.stream = 105
        assertEquals(1, nextConnection.target(105, 0))
        assertNull(nextConnection.target(103, 0))
    }
}
