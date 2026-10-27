package dev.ryuya.homepodbridge.halprobe;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public final class PeriodRouteGuardTest {
  private static final String OWN = "123:ap:7mixp:0";
  private static final List<PeriodRouteGuard.Port> PORTS =
      List.of(
          new PeriodRouteGuard.Port(3, 0, ""),
          new PeriodRouteGuard.Port(4, 0, ""),
          new PeriodRouteGuard.Port(10, 143, OWN),
          new PeriodRouteGuard.Port(11, 11, OWN),
          new PeriodRouteGuard.Port(12, 143, "foreign"));
  private static final PeriodRouteGuard.Config MIX =
      new PeriodRouteGuard.Config(20, 3, 44100, true, true, 91);
  private static final PeriodRouteGuard.Config DEVICE =
      new PeriodRouteGuard.Config(21, 10, 44100, true, true, 0);

  @Test
  public void approvesOnlyUniqueOwnedMixAndDevice() {
    assertTrue(PeriodRouteGuard.allows(PORTS, List.of(MIX, DEVICE), OWN, 20, 3));
    assertFalse(PeriodRouteGuard.allows(PORTS, List.of(MIX, DEVICE), OWN, 21, 3));
    assertFalse(PeriodRouteGuard.allows(PORTS, List.of(MIX, DEVICE), OWN, 20, 4));
    assertFalse(PeriodRouteGuard.allows(PORTS, List.of(MIX), OWN, 20, 3));
  }

  @Test
  public void foreignActiveDeviceAndAmbiguousMixRefuse() {
    var foreign = new PeriodRouteGuard.Config(22, 12, 44100, true, true, 0);
    assertFalse(PeriodRouteGuard.allows(PORTS, List.of(MIX, DEVICE, foreign), OWN, 20, 3));
    var otherMix = new PeriodRouteGuard.Config(23, 3, 44100, true, true, 92);
    assertFalse(PeriodRouteGuard.allows(PORTS, List.of(MIX, DEVICE, otherMix), OWN, 20, 3));
    assertFalse(PeriodRouteGuard.allows(PORTS, List.of(MIX, DEVICE, MIX), OWN, 20, 3));
  }

  @Test
  public void formatHandleAndRegistrationAreStrict() {
    for (var invalid :
        List.of(
            new PeriodRouteGuard.Config(20, 3, 48000, true, true, 91),
            new PeriodRouteGuard.Config(20, 3, 44100, false, true, 91),
            new PeriodRouteGuard.Config(20, 3, 44100, true, false, 91),
            new PeriodRouteGuard.Config(20, 3, 44100, true, true, 0)))
      assertFalse(PeriodRouteGuard.allows(PORTS, List.of(invalid, DEVICE), OWN, 20, 3));
    assertFalse(PeriodRouteGuard.allows(PORTS, List.of(MIX, DEVICE), "", 20, 3));
    assertFalse(PeriodRouteGuard.allows(PORTS, List.of(MIX, DEVICE), OWN, 0, 3));
  }
}
