package dev.ryuya.homepodbridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ShortPacketSettingsTest {
  @Test
  public void defaultAndFallbackPreserveTheNormalPacketSize() {
    var settings = new StreamSettings(true, 0, 2, 50, true).withDedicatedCapture(true);
    assertEquals(352, settings.requestedPacketFrames());
    var shortPackets = settings.withShortAudioPackets(true);
    assertEquals(88, shortPackets.packetFramesForBackend(true));
    assertEquals(352, shortPackets.packetFramesForBackend(false));
    assertEquals(352, shortPackets.withDedicatedCapture(false).requestedPacketFrames());
    assertEquals(352, shortPackets.withProfile(2).requestedPacketFrames());
    assertEquals(352, shortPackets.withShortAudioPackets(false).requestedPacketFrames());
  }

  @Test
  public void savedPreferenceSurvivesUnrelatedSettingsAndDoesNotChangeTheReserve() {
    var base = new StreamSettings(true, 0, 2, 50, true).withDedicatedCapture(true);
    var changed =
        base.withShortAudioPackets(true)
            .withWholeDevice(false)
            .withCompression(true)
            .withAdaptiveBuffer(true)
            .withDeviceBuffer(true)
            .withDeviceBufferFrames(928)
            .withReceiverTiming(new ReceiverTiming(true, 50, 80, 20));
    assertTrue(changed.shortAudioPackets());
    assertEquals(8, changed.effectiveBufferMs());
    assertTrue(changed.withBuffers(0, 2, 40).withProfile(1).shortAudioPackets());
    assertEquals(base.captureUnitMs(), changed.captureUnitMs(), 0.00001);
    assertEquals(20, changed.requestedPresentationMs());
  }
}
