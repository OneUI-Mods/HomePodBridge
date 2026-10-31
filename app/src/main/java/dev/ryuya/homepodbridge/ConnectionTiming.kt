package dev.ryuya.homepodbridge

import java.util.Arrays
import java.util.function.LongSupplier
import kotlin.jvm.JvmOverloads
import kotlin.jvm.Synchronized

/* Local connection stages may overlap; never sum them into a fabricated total. */
class ConnectionTiming
@JvmOverloads
constructor(clock: LongSupplier = LongSupplier { System.nanoTime() }) {
    enum class Stage {
        SELECTED,
        WORKER,
        NETWORK,
        CAPTURE,
        SENDER,
        AUTHENTICATED,
        READY,
    }

    private val times: LongArray = LongArray(ConnectionTiming.Stage.entries.size)
    private val clock: LongSupplier

    init {
        this.clock = clock
        Arrays.fill(times, -1)
        mark(ConnectionTiming.Stage.SELECTED)
    }

    @Synchronized
    fun mark(stage: ConnectionTiming.Stage) {
        markAt(stage, clock.getAsLong())
    }

    @Synchronized
    fun markAt(stage: ConnectionTiming.Stage, at: Long) {
        if (times[stage.ordinal] < 0 && at > 0 && (times[0] < 0 || at >= times[0]))
            times[stage.ordinal] = at
    }

    @Synchronized
    fun micros(from: ConnectionTiming.Stage, to: ConnectionTiming.Stage): Long {
        val a: Long = times[from.ordinal]
        val b: Long = times[to.ordinal]
        return if (a < 0 || b < a) -1 else (b - a) / 1000
    }

    @Synchronized
    fun summary(): String? {
        return ("connectionTimingMs queue=" +
            ms(ConnectionTiming.Stage.SELECTED, ConnectionTiming.Stage.WORKER) +
            " network=" +
            ms(ConnectionTiming.Stage.WORKER, ConnectionTiming.Stage.NETWORK) +
            " capture=" +
            ms(ConnectionTiming.Stage.NETWORK, ConnectionTiming.Stage.CAPTURE) +
            " handshake=" +
            ms(ConnectionTiming.Stage.SENDER, ConnectionTiming.Stage.AUTHENTICATED) +
            " total=" +
            ms(ConnectionTiming.Stage.SELECTED, ConnectionTiming.Stage.READY))
    }

    private fun ms(from: ConnectionTiming.Stage, to: ConnectionTiming.Stage): Long {
        val value = micros(from, to)
        return if (value < 0) -1 else value / 1000
    }
}
