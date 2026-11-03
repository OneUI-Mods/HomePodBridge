package dev.ryuya.homepodbridge.compat

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class GenerationStoreTest {
    private fun temporary(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("oneui-discovery-test").toFile()
        try {
            test(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun positiveAndNegativeBindingsReuseOneAttempt() = temporary { dir ->
        val store = GenerationStore(dir)
        val expected = mapOf("class" to "actual.Type", "missing" to null)
        assertEquals(expected, store.readOrExplore("v1") { expected }.bindings)
        assertEquals(expected, store.readOrExplore("v1") { error("repeated exploration") }.bindings)
        assertEquals(expected, store.readExisting("v1")!!.bindings)
    }

    @Test
    fun updatesPermitExactlyOneNewAttempt() = temporary { dir ->
        val count = AtomicInteger()
        for (version in
            listOf(
                "apk1-module1",
                "apk1-module1",
                "apk2-module1",
                "apk2-module2",
                "apk2-module2",
            )) {
            GenerationStore(dir).readOrExplore(version) {
                count.incrementAndGet()
                emptyMap()
            }
        }
        assertEquals(3, count.get())
    }

    @Test
    fun failedAndNativeUnavailableResultsAreTerminal() = temporary { dir ->
        val store = GenerationStore(dir)
        assertEquals(
            "failed",
            store.readOrExplore("failure") { throw IllegalStateException() }.state,
        )
        assertEquals("failed", store.readOrExplore("failure") { error("must not retry") }.state)
        assertEquals(
            "native-unavailable",
            store.readOrExplore("native") { throw UnsatisfiedLinkError() }.state,
        )
        assertEquals(
            "native-unavailable",
            store.readOrExplore("native") { error("must not retry") }.state,
        )
    }

    @Test
    fun interruptionClaimPreventsCrashLoop() = temporary { dir ->
        val store = GenerationStore(dir)
        try {
            store.readOrExplore("v1") { throw AssertionError("simulated abrupt termination") }
        } catch (_: AssertionError) {}
        assertEquals("interrupted", store.readOrExplore("v1") { error("must not retry") }.state)
    }

    @Test
    fun corruptionDoesNotCauseRepeatedExploration() = temporary { dir ->
        val store = GenerationStore(dir)
        store.readOrExplore("v1") { emptyMap() }
        File(dir, GenerationStore.digest("v1") + ".bin").writeBytes(byteArrayOf(0, 1))
        assertEquals("corrupt", store.readOrExplore("v1") { error("must not retry") }.state)
    }

    @Test
    fun concurrentInstancesShareOneExploration() = temporary { dir ->
        val count = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs =
                (1..32).map {
                    Callable {
                        GenerationStore(dir).readOrExplore("same") {
                            count.incrementAndGet()
                            Thread.sleep(10)
                            mapOf("result" to "one")
                        }
                    }
                }
            pool.invokeAll(jobs).forEach { assertEquals("one", it.get().bindings["result"]) }
            assertEquals(1, count.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun unicodeRoundTripPreservesNullAndEmpty() {
        val expected = mapOf("日本語" to "絵文字✅", "empty" to "", "negative" to null)
        val result = GenerationStore.Result("ready", expected)
        assertEquals(result, GenerationStore.decode(GenerationStore.encode(result)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedValuesAreRejected() {
        GenerationStore.encode(GenerationStore.Result("ready", mapOf("key" to "x".repeat(4097))))
    }
}
