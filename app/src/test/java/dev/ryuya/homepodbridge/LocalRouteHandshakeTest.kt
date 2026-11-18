package dev.ryuya.homepodbridge

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LocalRouteHandshakeTest {
    @Test
    fun successKeepsTheHelperUntilClose() {
        val child =
            ProcessBuilder("sh", "-c", "printf 'HOMEPOD_ROUTE_READY\\n'; read lifetime").start()
        val route = LocalRoute.awaitHelper(child, 2000)
        assertTrue(child.isAlive)
        route.close()
        assertTrue(child.waitFor(2, TimeUnit.SECONDS))
    }

    @Test
    fun aPartialLineCannotIgnoreTheDeadline() {
        val child = ProcessBuilder("sh", "-c", "printf 'ROOT PROMPT'; read lifetime").start()
        try {
            LocalRoute.awaitHelper(child, 100)
            fail("Deadline required")
        } catch (_: IOException) {}
        assertTrue(child.waitFor(2, TimeUnit.SECONDS))
    }

    @Test
    fun interruptionRunsTheHelperCleanupTrapAndPreservesInterrupt() {
        val marker = File.createTempFile("route-cleanup", ".txt").apply { delete() }
        val child =
            ProcessBuilder(
                    "sh",
                    "-c",
                    "trap 'printf done > \"\$1\"' EXIT; printf 'PREPARED\\n'; read lifetime",
                    "test",
                    marker.absolutePath,
                )
                .start()
        // Read the explicit child handshake before cancelling; no timing assumption.
        assertEquals("PREPARED", child.inputStream.bufferedReader().readLine())
        Thread.currentThread().interrupt()
        try {
            LocalRoute.awaitHelper(child, 2000)
            fail("Interrupt required")
        } catch (_: InterruptedException) {
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertTrue(child.waitFor(2, TimeUnit.SECONDS))
        assertEquals("done", marker.readText())
        marker.delete()
    }
}
