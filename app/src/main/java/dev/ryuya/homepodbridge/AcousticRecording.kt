package dev.ryuya.homepodbridge

import java.util.Arrays
import kotlin.jvm.Synchronized

/* Bounded microphone storage: clearing atomically prevents further writes and export. */
class AcousticRecording(capacity: Int) {
    private val samples: ShortArray
    private var count: Int = 0
    private var finished: Boolean = false
    private var cleared: Boolean = false

    init {
        require(capacity > 0)
        samples = ShortArray(capacity)
    }

    @Synchronized
    fun count(): Int {
        return count
    }

    fun capacity(): Int {
        return samples.size
    }

    @Synchronized
    fun append(source: ShortArray, length: Int): Boolean {
        if (cleared || finished) return false
        require(!(length < 0 || length > source.size || length > samples.size - count))
        System.arraycopy(source, 0, samples, count, length)
        count += length
        return true
    }

    @Synchronized
    fun finish() {
        finished = true
    }

    @Synchronized
    fun snapshot(): ShortArray? {
        return if (finished && !cleared) samples.copyOf(count) else null
    }

    @Synchronized
    fun clear() {
        cleared = true
        Arrays.fill(samples, 0.toShort())
        count = 0
    }
}
