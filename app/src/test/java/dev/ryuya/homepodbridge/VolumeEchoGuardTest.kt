package dev.ryuya.homepodbridge

import org.junit.Assert.*
import org.junit.Test

class VolumeEchoGuardTest {
    @Test
    fun oneImmediateOldPanelValueCannotUndoMute() {
        val guard = VolumeEchoGuard()
        guard.explicitChange(2, 0, 1000)
        assertTrue(guard.ignore(2, 1021))
        assertFalse(guard.ignore(2, 1040))
    }

    @Test
    fun newValuesExpiryAndNextConnectionAreNeverSuppressed() {
        val guard = VolumeEchoGuard()
        guard.explicitChange(2, 0, 0)
        assertFalse(guard.ignore(3, 10))
        assertFalse(guard.ignore(2, 20))
        guard.explicitChange(2, 0, 0)
        assertFalse(guard.ignore(2, 151))
        guard.explicitChange(0, 2, 200)
        guard.clear()
        assertFalse(guard.ignore(0, 210))
        guard.explicitChange(2, 2, 300)
        assertFalse(guard.ignore(2, 301))
    }
}
