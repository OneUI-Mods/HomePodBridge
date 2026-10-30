package dev.ryuya.homepodbridge.halprobe

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Criteria consumed directly by the policy builder; microphone input is never an option. */
@JvmRecord
data class CaptureScope(
    val whole: Boolean,
    val selectedUid: Int,
    val excludedUid: Int,
    val zeroOnly: Boolean,
    val pulse: Boolean,
) {
    fun matchedUsage(): Int {
        return if (whole) -1 else 1
    }

    fun excludedUsages(): IntArray? {
        return if (whole) intArrayOf(2, 3) else IntArray(0)
    }

    companion object {
        @JvmStatic
        fun parse(mode: String?, selectedUid: Int, bridgeUid: Int): CaptureScope? {
            require(!(bridgeUid <= 0 || selectedUid < 0))
            return when (mode) {
                "selected",
                "selected-short" -> CaptureScope(false, selectedUid, bridgeUid, false, false)
                "whole",
                "whole-short" -> CaptureScope(true, -1, bridgeUid, false, false)
                "test-target-752",
                "test-target-768",
                "test-selected",
                "test-selected-short" -> CaptureScope(false, 0, bridgeUid, true, false)
                "test-whole",
                "test-whole-short" -> CaptureScope(true, -1, bridgeUid, true, false)
                "test-cycle",
                "test-cycle-short" -> CaptureScope(false, 0, bridgeUid, true, true)
                else -> throw IllegalArgumentException("Unknown capture scope")
            }
        }
    }

    override fun toString(): String =
        "CaptureScope[whole=$whole, selectedUid=$selectedUid, excludedUid=$excludedUid, zeroOnly=$zeroOnly, pulse=$pulse]"
}
