package dev.ryuya.homepodbridge.halprobe

import java.util.LinkedHashMap
import java.util.List
import kotlin.jvm.JvmRecord
import kotlin.jvm.Synchronized

/* An undelivered open reply is potentially an allocation, never proof of no stream. */
class StreamOpenLedger(
    journal: StreamOpenLedger.Journal,
    recovered: MutableList<StreamOpenLedger.Entry?>,
) {
    enum class Phase {
        OPENING,
        HANDLE_RECEIVED,
        CLOSED,
    }

    @JvmRecord
    data class Entry(val configId: Int, val input: Boolean, val phase: StreamOpenLedger.Phase?) {
        override fun toString(): String = "Entry[configId=$configId, input=$input, phase=$phase]"
    }

    fun interface Journal {
        @Throws(Exception::class) fun persist(entries: MutableList<StreamOpenLedger.Entry?>?)
    }

    private val entries: MutableMap<Int?, StreamOpenLedger.Entry?> =
        LinkedHashMap<Int?, StreamOpenLedger.Entry?>()
    private val journal: StreamOpenLedger.Journal

    init {
        this.journal = journal
        for (entry in recovered) {
            require(
                !(entry!!.configId <= 0 || entries.putIfAbsent(entry!!.configId, entry) != null)
            ) {
                "Invalid stream journal"
            }
        }
    }

    @Synchronized
    @Throws(Exception::class)
    fun begin(config: Int, input: Boolean) {
        check(!(config <= 0 || entries.containsKey(config))) { "Open already attempted" }
        entries.put(config, StreamOpenLedger.Entry(config, input, StreamOpenLedger.Phase.OPENING))
        // Persist before Binder dispatch. Failure prevents dispatch and still fails closed.
        journal.persist(snapshot())
    }

    @Synchronized
    @Throws(Exception::class)
    fun received(config: Int) {
        transition(config, StreamOpenLedger.Phase.OPENING, StreamOpenLedger.Phase.HANDLE_RECEIVED)
    }

    @Synchronized
    @Throws(Exception::class)
    fun closed(config: Int) {
        transition(config, StreamOpenLedger.Phase.HANDLE_RECEIVED, StreamOpenLedger.Phase.CLOSED)
    }

    @Throws(Exception::class)
    private fun transition(
        config: Int,
        expected: StreamOpenLedger.Phase?,
        next: StreamOpenLedger.Phase?,
    ) {
        val previous = entries.get(config)
        check(!(previous == null || previous.phase !== expected)) { "Invalid stream transition" }
        // Journal first: a failed save must never report successful cleanup.
        val proposed = LinkedHashMap<Int?, StreamOpenLedger.Entry?>(entries)
        proposed.put(config, StreamOpenLedger.Entry(config, previous.input, next))
        journal.persist(List.copyOf<StreamOpenLedger.Entry?>(proposed.values))
        entries.clear()
        entries.putAll(proposed)
    }

    @Synchronized
    fun snapshot(): MutableList<StreamOpenLedger.Entry?>? {
        return List.copyOf<StreamOpenLedger.Entry?>(entries.values)
    }

    @Synchronized
    fun allClosed(): Boolean {
        return entries.values.stream().allMatch { e: StreamOpenLedger.Entry? ->
            e!!.phase === StreamOpenLedger.Phase.CLOSED
        }
    }

    @Synchronized
    fun attempted(): Boolean {
        return !entries.isEmpty()
    }

    @Synchronized
    fun replyUncertain(): Boolean {
        return entries.values.stream().anyMatch { e: StreamOpenLedger.Entry? ->
            e!!.phase === StreamOpenLedger.Phase.OPENING
        }
    }
}
