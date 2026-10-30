package dev.ryuya.homepodbridge.halprobe

import org.junit.Assert.*
import org.junit.Test

class CaptureScopeTest {
    @Test
    fun selectedScopeMatchesOnlyTheResolvedUidAndMediaUsage() {
        val scope = CaptureScope.parse("selected", 12345, 10234)
        assertFalse(scope!!.whole)
        assertEquals(12345, scope!!.selectedUid)
        assertEquals(1, scope!!.matchedUsage())
        assertEquals(0, scope!!.excludedUsages()!!.size)
        assertFalse(scope.zeroOnly)
    }

    @Test
    fun wholeScopeExcludesBridgeAndBothCallUsages() {
        val scope = CaptureScope.parse("whole", 12345, 10234)
        assertTrue(scope!!.whole)
        assertEquals(10234, scope!!.excludedUid)
        assertArrayEquals(intArrayOf(2, 3), scope!!.excludedUsages())
        assertEquals(-1, scope!!.matchedUsage())
    }

    @Test
    fun zeroSourcesCannotWidenTheNormalSelectedScope() {
        val scope = CaptureScope.parse("test-cycle", 12345, 10234)
        assertTrue(scope!!.zeroOnly)
        assertTrue(scope!!.pulse)
        assertEquals(0, scope!!.selectedUid)
        assertFalse(scope!!.whole)
    }

    @Test
    fun shortPeriodPreservesEverySourceAndExclusionRule() {
        for (mode in
            arrayOf<String>(
                "selected",
                "whole",
                "test-selected",
                "test-whole",
                "test-cycle",
            )) assertEquals(
            CaptureScope.parse(mode, 12345, 10234),
            CaptureScope.parse(mode + "-short", 12345, 10234),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnrecognizedScope() {
        CaptureScope.parse("microphone", 12345, 10234)
    }
}
