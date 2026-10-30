package dev.ryuya.homepodbridge

import java.util.List
import org.junit.Assert.*
import org.junit.Test

class VolumeKeyChangesTest {
    @Test
    fun applicationAndRestorationWritesCannotResetHomePod() {
        val events = VolumeKeyChanges(0)
        events.changed(0, 0)
        events.changed(20, 10)
        events.changed(0, 12)
        assertTrue(events.drain(40).isEmpty())
        assertTrue(events.drain(500).isEmpty())
    }

    @Test
    fun relativeKeysPreserveRemoteVolumeAndIgnoreRestoration() {
        val events = VolumeKeyChanges(0)
        events.changed(2, 100)
        events.changed(0, 102)
        events.key(101)
        assertTrue(events.drain(120).isEmpty())
        assertEquals(java.util.List.of(1), events.drain(130))
        events.changed(0, 300)
        events.key(301)
        assertEquals(List.of(-1), events.drain(330))
        assertTrue(events.drain(600).isEmpty())
    }

    @Test
    fun callbackOrderAndNonzeroLocalBaselineAreSupported() {
        val events = VolumeKeyChanges(50)
        events.key(0)
        events.changed(48, 5)
        events.changed(50, 7)
        assertEquals(List.of(-1), events.drain(30))
        events.key(200)
        events.changed(52, 206)
        assertEquals(java.util.List.of(1), events.drain(230))
    }

    @Test
    fun upperBoundaryStillAllowsRaisingRemoteVolume() {
        val events = VolumeKeyChanges(100)
        events.changed(100, 0)
        events.key(1)
        assertEquals(java.util.List.of(1), events.drain(30))
    }
}
