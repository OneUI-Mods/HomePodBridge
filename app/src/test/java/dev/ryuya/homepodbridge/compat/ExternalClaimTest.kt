package dev.ryuya.homepodbridge.compat

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class ExternalClaimTest {
    private fun temporary(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("module-owned-discovery").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun onlyClaimantCanFinishAndCompletedResultCannotBeOverwritten() = temporary { directory ->
        val store = GenerationStore(directory)
        val token = store.beginExternal("v1").token!!
        val result =
            GenerationStore.Result("ready", mapOf("found" to "live.Type", "missing" to null))
        assertFalse(store.finishExternal("v1", "wrong", result))
        assertTrue(store.finishExternal("v1", token, result))
        assertFalse(store.finishExternal("v1", token, GenerationStore.Result("failed")))
        assertNull(store.beginExternal("v1").token)
        assertEquals(result, store.beginExternal("v1").result)
        assertEquals(result, store.readOrExplore("v1") { error("must not explore") })
    }

    @Test
    fun lostClaimIsTerminalAcrossStoreInstances() = temporary { directory ->
        assertNotNull(GenerationStore(directory).beginExternal("v1").token)
        val reopened = GenerationStore(directory).beginExternal("v1")
        assertNull(reopened.token)
        assertEquals("interrupted", reopened.result.state)
        assertTrue(reopened.result.bindings.isEmpty())
        assertNotNull(GenerationStore(directory).beginExternal("v2").token)
    }

    @Test
    fun concurrentHostsHaveOneClaimEvenWhenCreatingTheDirectoryTogether() = temporary { parent ->
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(50) { iteration ->
                val directory = File(parent, "first-start-$iteration")
                val results =
                    pool
                        .invokeAll(
                            (1..16).map {
                                Callable {
                                    GenerationStore(directory).beginExternal("same-version")
                                }
                            }
                        )
                        .map { it.get() }
                assertEquals(1, results.count { it.token != null })
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun nativeFailureAndCorruptionNeverIssueAnotherClaim() = temporary { directory ->
        val store = GenerationStore(directory)
        val token = store.beginExternal("native").token!!
        assertTrue(
            store.finishExternal("native", token, GenerationStore.Result("native-unavailable"))
        )
        assertNull(store.beginExternal("native").token)
        val broken = store.beginExternal("broken").token!!
        File(directory, GenerationStore.digest("broken") + ".bin").writeText("invalid")
        assertEquals("corrupt", store.beginExternal("broken").result.state)
        assertFalse(store.finishExternal("broken", broken, GenerationStore.Result("ready")))
    }
}
