package dev.ryuya.homepodbridge.halprobe

import java.util.List
import org.junit.Assert.*
import org.junit.Test

class PeriodRouteGuardTest {
    @Test
    fun approvesOnlyUniqueOwnedMixAndDevice() {
        assertTrue(
            PeriodRouteGuard.allows(
                PeriodRouteGuardTest.PORTS!!,
                List.of<PeriodRouteGuard.Config?>(
                    PeriodRouteGuardTest.MIX,
                    PeriodRouteGuardTest.DEVICE,
                ),
                PeriodRouteGuardTest.OWN,
                20,
                3,
            )
        )
        assertFalse(
            PeriodRouteGuard.allows(
                PeriodRouteGuardTest.PORTS!!,
                List.of<PeriodRouteGuard.Config?>(
                    PeriodRouteGuardTest.MIX,
                    PeriodRouteGuardTest.DEVICE,
                ),
                PeriodRouteGuardTest.OWN,
                21,
                3,
            )
        )
        assertFalse(
            PeriodRouteGuard.allows(
                PeriodRouteGuardTest.PORTS!!,
                List.of<PeriodRouteGuard.Config?>(
                    PeriodRouteGuardTest.MIX,
                    PeriodRouteGuardTest.DEVICE,
                ),
                PeriodRouteGuardTest.OWN,
                20,
                4,
            )
        )
        assertFalse(
            PeriodRouteGuard.allows(
                PeriodRouteGuardTest.PORTS!!,
                List.of<PeriodRouteGuard.Config?>(PeriodRouteGuardTest.MIX),
                PeriodRouteGuardTest.OWN,
                20,
                3,
            )
        )
    }

    @Test
    fun foreignActiveDeviceAndAmbiguousMixRefuse() {
        val foreign = PeriodRouteGuard.Config(22, 12, 44100, true, true, 0)
        assertFalse(
            PeriodRouteGuard.allows(
                PeriodRouteGuardTest.PORTS!!,
                List.of<PeriodRouteGuard.Config?>(
                    PeriodRouteGuardTest.MIX,
                    PeriodRouteGuardTest.DEVICE,
                    foreign,
                ),
                PeriodRouteGuardTest.OWN,
                20,
                3,
            )
        )
        val otherMix = PeriodRouteGuard.Config(23, 3, 44100, true, true, 92)
        assertFalse(
            PeriodRouteGuard.allows(
                PeriodRouteGuardTest.PORTS!!,
                List.of<PeriodRouteGuard.Config?>(
                    PeriodRouteGuardTest.MIX,
                    PeriodRouteGuardTest.DEVICE,
                    otherMix,
                ),
                PeriodRouteGuardTest.OWN,
                20,
                3,
            )
        )
        assertFalse(
            PeriodRouteGuard.allows(
                PeriodRouteGuardTest.PORTS!!,
                List.of<PeriodRouteGuard.Config?>(
                    PeriodRouteGuardTest.MIX,
                    PeriodRouteGuardTest.DEVICE,
                    PeriodRouteGuardTest.MIX,
                ),
                PeriodRouteGuardTest.OWN,
                20,
                3,
            )
        )
    }

    @Test
    fun formatHandleAndRegistrationAreStrict() {
        for (invalid in
            List.of<PeriodRouteGuard.Config?>(
                PeriodRouteGuard.Config(20, 3, 48000, true, true, 91),
                PeriodRouteGuard.Config(20, 3, 44100, false, true, 91),
                PeriodRouteGuard.Config(20, 3, 44100, true, false, 91),
                PeriodRouteGuard.Config(20, 3, 44100, true, true, 0),
            )) assertFalse(
            PeriodRouteGuard.allows(
                PeriodRouteGuardTest.PORTS!!,
                List.of<PeriodRouteGuard.Config?>(invalid, PeriodRouteGuardTest.DEVICE),
                PeriodRouteGuardTest.OWN,
                20,
                3,
            )
        )
        assertFalse(
            PeriodRouteGuard.allows(
                PeriodRouteGuardTest.PORTS!!,
                List.of<PeriodRouteGuard.Config?>(
                    PeriodRouteGuardTest.MIX,
                    PeriodRouteGuardTest.DEVICE,
                ),
                "",
                20,
                3,
            )
        )
        assertFalse(
            PeriodRouteGuard.allows(
                PeriodRouteGuardTest.PORTS!!,
                List.of<PeriodRouteGuard.Config?>(
                    PeriodRouteGuardTest.MIX,
                    PeriodRouteGuardTest.DEVICE,
                ),
                PeriodRouteGuardTest.OWN,
                0,
                3,
            )
        )
    }

    companion object {
        private const val OWN: String = "123:ap:7mixp:0"
        private val PORTS: MutableList<PeriodRouteGuard.Port?>? =
            List.of<PeriodRouteGuard.Port?>(
                PeriodRouteGuard.Port(3, 0, ""),
                PeriodRouteGuard.Port(4, 0, ""),
                PeriodRouteGuard.Port(10, 143, PeriodRouteGuardTest.OWN),
                PeriodRouteGuard.Port(11, 11, PeriodRouteGuardTest.OWN),
                PeriodRouteGuard.Port(12, 143, "foreign"),
            )
        private val MIX: PeriodRouteGuard.Config =
            PeriodRouteGuard.Config(20, 3, 44100, true, true, 91)
        private val DEVICE: PeriodRouteGuard.Config =
            PeriodRouteGuard.Config(21, 10, 44100, true, true, 0)
    }
}
