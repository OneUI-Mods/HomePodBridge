package dev.ryuya.homepodbridge

import org.junit.Assert.*
import org.junit.Test

class MediaSessionChoiceTest {
    @Test
    fun selectedModeCannotRevealAnotherPackage() {
        val sessions =
            listOf(MediaSessionChoice("other.player", 3), MediaSessionChoice("music.player", 2))
        assertEquals(1, MediaSessionChoice.choose(sessions, "music.player", false))
        assertNull(MediaSessionChoice.choose(sessions, "missing.player", false))
    }

    @Test
    fun wholeModeRejectsTheBridgeAndVoiceAndPrefersActualPlayback() {
        val sessions =
            listOf(
                MediaSessionChoice(RootProtocol.PACKAGE, 3),
                MediaSessionChoice("call.player", 3, true),
                MediaSessionChoice("music.player", 2),
                MediaSessionChoice("other.player", 3),
            )
        assertEquals(3, MediaSessionChoice.choose(sessions, "music.player", true))
    }

    @Test
    fun wholeModeDoesNotMistakePausedMusicForGameOrNotificationAudio() {
        val paused = listOf(MediaSessionChoice("music.player", 2))
        assertNull(MediaSessionChoice.choose(paused, "music.player", true))
        assertEquals(0, MediaSessionChoice.choose(paused, "music.player", false))
    }

    @Test
    fun equalPriorityPrefersSelectedSourceThenSystemOrder() {
        val sessions =
            listOf(MediaSessionChoice("other.player", 3), MediaSessionChoice("music.player", 3))
        assertEquals(1, MediaSessionChoice.choose(sessions, "music.player", true))
        assertEquals(0, MediaSessionChoice.choose(sessions, null, true))
        assertNull(
            MediaSessionChoice.choose(
                listOf(MediaSessionChoice("music.player", 1)),
                "music.player",
                true,
            )
        )
    }
}
