package dev.ryuya.homepodbridge

import org.junit.Assert.*
import org.junit.Test

open class NetworkTimingTest {
    @Test
    open fun missingAndInsufficientSamplesCannotClaimZeroLatency() {
        assertEquals(-1, NetworkTiming(null, 0).transit(6))
        assertEquals(-1, NetworkTiming(LongArray(13), 0).value(0))
        val data = LongArray(17)
        data[5] = 4
        assertEquals(-1, NetworkTiming(data, 0).transit(7))
        data[5] = 5
        data[7] = 17
        assertEquals(17, NetworkTiming(data, 0).transit(7))
        assertEquals(-1, NetworkTiming(data, 0).transit(3))
    }

    @Test
    open fun requestReadbackAndRadioEligibilityStayDistinct() {
        val data = LongArray(17)
        data[9] = 1
        data[10] = 184
        data[11] = 6
        val active = NetworkTiming(data, 2)
        assertEquals(R.string.network_priority_accepted, active.priorityResource())
        assertEquals(R.string.network_wifi_eligible, active.wifiResource())
        assertEquals(R.string.network_wifi_waiting, NetworkTiming(data, 1).wifiResource())
        assertEquals(R.string.network_wifi_not_wifi, NetworkTiming(data, 3).wifiResource())
        assertEquals(R.string.network_wifi_denied, NetworkTiming(data, 4).wifiResource())
        data[11] = 0
        assertEquals(R.string.network_priority_unavailable, active.priorityResource())
        data[9] = 0
        assertEquals(R.string.network_normal, active.priorityResource())
        assertEquals(R.string.network_wifi_off, active.wifiResource())
    }
}
