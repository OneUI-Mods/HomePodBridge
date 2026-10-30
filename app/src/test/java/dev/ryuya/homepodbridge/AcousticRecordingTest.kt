package dev.ryuya.homepodbridge

import org.junit.Assert.*
import org.junit.Test

class AcousticRecordingTest {
    @Test
    fun onlyFinishedBoundedCopiesCanBeExported() {
        val data = AcousticRecording(4)
        assertNull(data.snapshot())
        assertTrue(data.append(shortArrayOf(1, 2), 2))
        assertThrows(
            IllegalArgumentException::class.java,
            { data.append(shortArrayOf(3, 4, 5), 3) },
        )
        assertTrue(data.append(shortArrayOf(3, 4), 2))
        data.finish()
        assertFalse(data.append(shortArrayOf(5), 1))
        val copy = data.snapshot()
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), copy)
        copy!![0] = 99
        assertEquals(1, data.snapshot()!![0].toInt())
    }

    @Test
    fun clearBeforeFinishPreventsLaterWritesAndExport() {
        val data = AcousticRecording(4)
        data.append(shortArrayOf(1, 2), 2)
        data.clear()
        assertFalse(data.append(shortArrayOf(3), 1))
        data.finish()
        assertNull(data.snapshot())
        assertEquals(0, data.count())
        data.clear()
    }

    @Test
    fun clearAfterFinishRevokesFutureExports() {
        val data = AcousticRecording(4)
        data.append(shortArrayOf(1, 2), 2)
        data.finish()
        assertNotNull(data.snapshot())
        data.clear()
        assertNull(data.snapshot())
        assertEquals(0, data.count())
    }
}
