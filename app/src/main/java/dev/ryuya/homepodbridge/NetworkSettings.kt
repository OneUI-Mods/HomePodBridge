package dev.ryuya.homepodbridge

import android.content.Context
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* Independent of the user's audited capture, codec and receiver timing settings. */
@JvmRecord
data class NetworkSettings(val priority: Boolean) {
    fun save(context: Context) {
        context
            .getSharedPreferences("network", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("priority", priority)
            .apply()
    }

    companion object {
        @JvmStatic
        fun load(context: Context): NetworkSettings? {
            return NetworkSettings(
                context
                    .getSharedPreferences("network", Context.MODE_PRIVATE)
                    .getBoolean("priority", false)
            )
        }
    }

    override fun toString(): String = "NetworkSettings[priority=$priority]"
}
