package dev.ryuya.homepodbridge

import android.content.Context
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Numeric receiver replies only; never formats native logs or infers rejection from silence. */
@JvmRecord
data class ReceiverRejection(val code: Int, val terminal: Boolean) {
    fun save(context: Context) {
        context
            .getSharedPreferences("receiverResponse", Context.MODE_PRIVATE)
            .edit()
            .putInt("code", code)
            .putBoolean("terminal", terminal)
            .apply()
    }

    fun message(context: Context, connected: Boolean): String? {
        val resource = messageResource(connected)
        return if (resource == 0) "" else context.getString(resource, code)
    }

    fun messageResource(connected: Boolean): Int {
        if (code === 0) return 0
        return if (terminal) R.string.receiver_request_rejected
        else if (connected) R.string.receiver_request_compatibility
        else R.string.receiver_request_last_retry
    }

    init {

        require(!(code != 0 && (code < 400 || code > 599)))
        require(!(code == 0 && terminal))
    }

    companion object {
        @JvmField val NONE: ReceiverRejection = ReceiverRejection(0, false)

        @JvmStatic
        fun fromNative(value: IntArray?): ReceiverRejection? {
            if (
                value == null ||
                    value.size != 2 ||
                    value[0] < 400 ||
                    value[0] > 599 ||
                    (value[1] != 0 && value[1] != 1)
            )
                return ReceiverRejection.NONE
            return ReceiverRejection(value[0], value[1] == 1)
        }

        @JvmStatic
        fun load(context: Context): ReceiverRejection? {
            val preferences = context.getSharedPreferences("receiverResponse", Context.MODE_PRIVATE)
            return ReceiverRejection.fromNative(
                intArrayOf(
                    preferences.getInt("code", 0),
                    if (preferences.getBoolean("terminal", false)) 1 else 0,
                )
            )
        }
    }

    override fun toString(): String = "ReceiverRejection[code=$code, terminal=$terminal]"
}
