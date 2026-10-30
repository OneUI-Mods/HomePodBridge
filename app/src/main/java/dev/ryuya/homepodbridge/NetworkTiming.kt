package dev.ryuya.homepodbridge

import kotlin.jvm.JvmRecord

/* Independent local TX evidence. No conversion of queue bytes/RTT to one-way audio delay. */
@JvmRecord
data class NetworkTiming(val values: LongArray?, val wifiRequest: Int) {
    fun value(index: Int): Long {
        return if (values != null && values.size === 17 && index >= 0 && index < 17) values[index]
        else -1
    }

    fun transit(index: Int): Long {
        return if (index >= 6 && index <= 8 && value(5) >= 5) value(index) else -1
    }

    fun priorityResource(): Int {
        if (value(9) < 0) return R.string.network_unknown
        if (value(9) == 0L) return R.string.network_normal
        return if ((value(10) and 0xfcL) == 184L && value(11) == 6L)
            R.string.network_priority_accepted
        else R.string.network_priority_unavailable
    }

    fun wifiResource(): Int {
        if (value(9) == 0L) return R.string.network_wifi_off
        if (wifiRequest === 2) return R.string.network_wifi_eligible
        if (wifiRequest === 1) return R.string.network_wifi_waiting
        if (wifiRequest === 3) return R.string.network_wifi_not_wifi
        if (wifiRequest === 4) return R.string.network_wifi_denied
        return R.string.network_wifi_unavailable
    }

    override fun toString(): String = "NetworkTiming[values=$values, wifiRequest=$wifiRequest]"

    override fun hashCode(): Int {
        var result = 0
        result = 31 * result + values.hashCode()
        result = 31 * result + wifiRequest.hashCode()
        return result
    }
}
