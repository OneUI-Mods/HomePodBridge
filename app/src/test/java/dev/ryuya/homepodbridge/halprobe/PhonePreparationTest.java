package dev.ryuya.homepodbridge.halprobe;

import static org.junit.Assert.*;

import org.junit.Test;

public class PhonePreparationTest {
  @Test
  public void closedPreparationPreservesBoundsAndReverseOperation() {
    var plan = PhonePreparation.parse("prepare-phone-1-88");
    assertNotNull(plan);
    assertEquals(10088, plan.reserveOperation());
    assertEquals(30096, plan.windowOperation());
    assertEquals(20088, plan.rollbackOperation());
    assertEquals("prepare-phone-1-88", plan.command());
    assertNotNull(PhonePreparation.parse("prepare-phone-21-800"));
    for (String bad :
        new String[] {
          "prepare-phone-0-88",
          "prepare-phone-22-88",
          "prepare-phone-1-63",
          "prepare-phone-1-801",
          "prepare-phone-1-88;anything",
          "window-96"
        }) assertNull(PhonePreparation.parse(bad));
  }
}
