package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public class NetworkTimingTest {
  @Test
  public void missingAndInsufficientSamplesCannotClaimZeroLatency() {
    assertEquals(-1, new NetworkTiming(null, 0).transit(6));
    assertEquals(-1, new NetworkTiming(new long[13], 0).value(0));
    long[] data = new long[17];
    data[5] = 4;
    assertEquals(-1, new NetworkTiming(data, 0).transit(7));
    data[5] = 5;
    data[7] = 17;
    assertEquals(17, new NetworkTiming(data, 0).transit(7));
    assertEquals(-1, new NetworkTiming(data, 0).transit(3));
  }

  @Test
  public void requestReadbackAndRadioEligibilityStayDistinct() {
    long[] data = new long[17];
    data[9] = 1;
    data[10] = 184;
    data[11] = 6;
    var active = new NetworkTiming(data, 2);
    assertEquals(R.string.network_priority_accepted, active.priorityResource());
    assertEquals(R.string.network_wifi_eligible, active.wifiResource());
    assertEquals(R.string.network_wifi_waiting, new NetworkTiming(data, 1).wifiResource());
    data[11] = 0;
    assertEquals(R.string.network_priority_unavailable, active.priorityResource());
    data[9] = 0;
    assertEquals(R.string.network_normal, active.priorityResource());
    assertEquals(R.string.network_wifi_off, active.wifiResource());
  }
}
