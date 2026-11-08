package dev.ryuya.homepodbridge.compat

import java.io.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One durable attempt per generation, including misses, interruption and corrupt results. */
class GenerationStore(private val directory: File) {
    data class Result(val state: String, val bindings: Map<String, String?> = emptyMap())

    fun readExisting(generation: String): Result? {
        val file = File(directory, digest(generation) + ".bin")
        return if (file.exists()) read(file) else null
    }

    private fun <T> locked(generation: String, block: (File) -> T): T {
        // mkdirs can return false when another process just created the directory.
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) {
            "Cache directory unavailable"
        }
        val key = digest(generation)
        return synchronized(
            locks.computeIfAbsent(File(directory, "$key.bin").absolutePath) { Any() },
        ) {
            FileChannel.open(
                    File(directory, "$key.lock").toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
                .use { channel ->
                    channel.lock().use { block(File(directory, "$key.bin")) }
                }
        }
    }

    fun readOrExplore(generation: String, explore: () -> Map<String, String?>): Result =
        locked(generation) { file ->
            if (file.exists()) return@locked read(file)
            // Persist the claim before touching native code. An interrupted search is terminal.
            write(file, Result("interrupted"))
            val result =
                try {
                    Result("ready", explore())
                } catch (_: Exception) {
                    Result("failed")
                } catch (_: LinkageError) {
                    Result("native-unavailable")
                }
            write(file, result)
            result
        }

    data class ExternalClaim(val result: Result, val token: String?)

    /** The module owns the file even when native exploration runs in a hooked host process. */
    fun beginExternal(generation: String): ExternalClaim =
        locked(generation) { file ->
            if (file.exists()) {
                val result = read(file)
                return@locked ExternalClaim(
                    if (result.state == "interrupted") Result("interrupted") else result,
                    null,
                )
            }
            val token = java.util.UUID.randomUUID().toString()
            write(file, Result("interrupted", mapOf("claim" to token)))
            ExternalClaim(Result("interrupted"), token)
        }

    /** A duplicate, stale or lost claimant can never overwrite a completed result. */
    fun finishExternal(generation: String, token: String, result: Result): Boolean =
        locked(generation) { file ->
            require(result.state in setOf("ready", "failed", "native-unavailable"))
            if (!file.exists()) return@locked false
            val current = read(file)
            if (current.state != "interrupted" || current.bindings["claim"] != token)
                return@locked false
            write(file, result)
            true
        }

    private fun read(file: File): Result =
        try {
            check(file.length() <= MAX_BYTES)
            decode(file.readBytes())
        } catch (_: Exception) {
            Result("corrupt")
        }

    private fun write(file: File, result: Result) {
        val bytes = encode(result)
        val pending = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(pending).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(
            pending.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        const val MAX_BYTES = 16 * 1024 * 1024
        private const val MAGIC = 0x4f554431

        fun digest(value: String): String {
            val bytes =
                MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            val output = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val byte = bytes[i].toInt() and 255
                output[i * 2] = hex[byte ushr 4]
                output[i * 2 + 1] = hex[byte and 15]
            }
            return output.concatToString()
        }

        fun encode(result: Result): ByteArray =
            ByteArrayOutputStream()
                .also { bytes ->
                    GZIPOutputStream(bytes).use { gzip ->
                        DataOutputStream(gzip).use { out ->
                            require(result.bindings.size <= 40_000)
                            out.writeInt(MAGIC)
                            out.writeUTF(result.state)
                            out.writeInt(result.bindings.size)
                            for ((key, value) in result.bindings.toSortedMap()) {
                                require(key.length <= 4096 && (value?.length ?: 0) <= 4096)
                                out.writeUTF(key)
                                out.writeBoolean(value != null)
                                if (value != null) out.writeUTF(value)
                            }
                        }
                    }
                }
                .toByteArray()

        fun decode(bytes: ByteArray): Result {
            require(bytes.size <= MAX_BYTES)
            // Bound decompression before parsing; no Java object deserialization is used.
            val expanded =
                GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(out.size() + count <= MAX_BYTES)
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
            return DataInputStream(ByteArrayInputStream(expanded)).use { input ->
                require(input.readInt() == MAGIC)
                val state = input.readUTF()
                require(state in setOf("ready", "failed", "interrupted", "native-unavailable"))
                val count = input.readInt()
                require(count in 0..40_000)
                val map = linkedMapOf<String, String?>()
                repeat(count) {
                    val key = input.readUTF()
                    require(key.length <= 4096 && !map.containsKey(key))
                    val value = if (input.readBoolean()) input.readUTF() else null
                    require((value?.length ?: 0) <= 4096)
                    map[key] = value
                }
                require(input.available() == 0)
                Result(state, map)
            }
        }
    }
}
