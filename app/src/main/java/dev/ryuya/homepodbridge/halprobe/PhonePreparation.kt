package dev.ryuya.homepodbridge.halprobe

import java.util.regex.Pattern
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Closed two-operation plan; native ownership/exclusion checks still run for each field. */
@JvmRecord
data class PhonePreparation(val timing: PhoneTiming?) {
    fun command(): String? {
        return "prepare-phone-" + timing!!.periodMs + "-" + timing!!.reserveFrames
    }

    fun reserveOperation(): Int {
        return 10000 + timing!!.reserveFrames
    }

    fun windowOperation(): Int {
        return 30000 + timing!!.latencyWindowFrames()
    }

    fun rollbackOperation(): Int {
        return 20000 + timing!!.reserveFrames
    }

    companion object {
        @JvmStatic
        fun parse(value: String?): PhonePreparation? {
            val match = Pattern.compile("prepare-phone-([0-9]{1,2})-([0-9]{2,3})").matcher(value)
            if (!match.matches()) return null
            try {
                return PhonePreparation(PhoneTiming(match.group(1).toInt(), match.group(2).toInt()))
            } catch (invalid: IllegalArgumentException) {
                return null
            }
        }
    }

    override fun toString(): String = "PhonePreparation[timing=$timing]"
}
