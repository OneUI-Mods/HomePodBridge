package dev.ryuya.homepodbridge.compat

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationDigestTest {
    @Test
    fun cacheNamesRemainCompatibleWithExistingUtf8Sha256Generations() {
        val vectors =
            mapOf(
                "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "abc" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "Galaxy 📱 日本語" to
                    "4494c6d5ba29c73851d51c7b57d60ebf586909d28395698459e4e2e6246f50c7",
            )
        for ((generation, expected) in vectors) assertEquals(
            expected,
            GenerationStore.digest(generation),
        )
    }
}
