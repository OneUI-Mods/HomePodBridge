package dev.ryuya.homepodbridge

import java.util.ArrayList
import kotlin.jvm.JvmRecord
import kotlin.math.abs

/* Pairs local-volume notifications with explicit key UI events, never with app volume writes. */
class VolumeKeyChanges(baseline: Int) {
    @JvmRecord
    private data class Change(val percent: Int, val atMs: Long) {
        override fun toString(): String = "Change[percent=$percent, atMs=$atMs]"
    }

    private val changes: MutableList<VolumeKeyChanges.Change?> =
        ArrayList<VolumeKeyChanges.Change?>()
    private val keys: MutableList<Long?> = ArrayList<Long?>()
    private val baseline: Int

    init {
        this.baseline = VolumePolicy.apply(baseline)
    }

    fun changed(percent: Int, now: Long) {
        if (changes.size == 32) changes.removeAt(0)
        changes.add(VolumeKeyChanges.Change(VolumePolicy.apply(percent), now))
    }

    fun key(now: Long) {
        if (keys.size == 16) keys.removeAt(0)
        keys.add(now)
    }

    fun pendingKeys(): Boolean {
        return !keys.isEmpty()
    }

    fun drain(now: Long): MutableList<Int?> {
        val directions = ArrayList<Int?>()
        while (!keys.isEmpty() && now - keys.get(0)!! >= 24) {
            val key = keys.get(0)
            var best: VolumeKeyChanges.Change? = null
            var bestDistance: Long = Long.MAX_VALUE
            for (change in changes) {
                val distance = Math.abs(change!!.atMs - key!!)
                if (distance > 100) continue
                // Prefer the actual adjustment over the restoration of our unchanged baseline.
                val score: Long = distance + (if (change!!.percent === baseline) 100 else 0)
                if (score < bestDistance) {
                    best = change
                    bestDistance = score
                }
            }
            if (best == null && now - key!! < 160) break
            keys.removeAt(0)
            if (best == null) continue
            changes.remove(best)
            var direction = Integer.compare(best.percent, baseline)
            if (direction == 0) direction = if (baseline == 0) -1 else if (baseline == 100) 1 else 0
            if (direction != 0) directions.add(direction)
        }
        changes.removeIf { change: VolumeKeyChanges.Change? -> now - change!!.atMs > 200 }
        return directions
    }
}
