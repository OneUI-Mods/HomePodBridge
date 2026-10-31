package dev.ryuya.homepodbridge.halprobe

import org.junit.Assert.assertThrows
import org.junit.Test

class ReadOnlyHalStateTest {
    @Test
    fun absentVendorModuleReachesTheExistingSecurityGuard() {
        assertThrows(SecurityException::class.java) { ReadOnlyHalState() }
    }
}
