package dev.ryuya.homepodbridge.startup

import org.junit.Assert.assertEquals
import org.junit.Test

class UnlockStartupTest {
    @Test
    fun unlockedHostInstallsImmediatelyWithoutAReceiver() {
        var calls = 0
        UnlockStartup({ true }, { error("No receiver needed") }).run { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun lockedHostWaitsAndDuplicateSignalsInstallOnlyOnce() {
        var unlocked = false
        var calls = 0
        var disposed = 0
        lateinit var signal: () -> Unit
        UnlockStartup(
                { unlocked },
                { callback ->
                    signal = callback
                    val removeListener: () -> Unit = { disposed++ }
                    removeListener
                },
            )
            .run { calls++ }
        signal()
        assertEquals(0, calls)
        unlocked = true
        signal()
        signal()
        assertEquals(1, calls)
        assertEquals(1, disposed)
    }

    @Test
    fun unlockBetweenCheckAndRegistrationDoesNotLoseStartup() {
        var unlocked = false
        var calls = 0
        var disposed = 0
        UnlockStartup(
                { unlocked },
                {
                    unlocked = true
                    val removeListener: () -> Unit = { disposed++ }
                    removeListener
                },
            )
            .run { calls++ }
        assertEquals(1, calls)
        assertEquals(1, disposed)
    }

    @Test
    fun synchronousSignalDuringRegistrationDisposesTheNewReceiverOnce() {
        var unlocked = false
        var calls = 0
        var disposed = 0
        UnlockStartup(
                { unlocked },
                { callback ->
                    unlocked = true
                    callback()
                    callback()
                    val removeListener: () -> Unit = { disposed++ }
                    removeListener
                },
            )
            .run { calls++ }
        assertEquals(1, calls)
        assertEquals(1, disposed)
    }
}
