package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class LocalRouteTest {
  @Test
  public void acceptsOnlyLocalIpv4Destinations() {
    assertTrue(LocalRoute.isLocalIpv4("192.168.10.50"));
    assertTrue(LocalRoute.isLocalIpv4("10.0.0.2"));
    assertTrue(LocalRoute.isLocalIpv4("172.16.0.2"));
    assertFalse(LocalRoute.isLocalIpv4("172.32.0.2"));
    assertFalse(LocalRoute.isLocalIpv4("8.8.8.8"));
    assertFalse(LocalRoute.isLocalIpv4("100.94.1.2"));
    assertFalse(LocalRoute.isLocalIpv4("192.168.10.999"));
    assertFalse(LocalRoute.isLocalIpv4("192.168.10.50;id"));
    assertFalse(LocalRoute.isLocalIpv4("$(id)"));
    assertFalse(LocalRoute.isLocalIpv4(null));
  }
}
