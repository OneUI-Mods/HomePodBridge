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

  @Test
  public void oneMillisecondPacketsRemainManualAndSurviveCopies() {
    var settings =
        new StreamSettings(true, 0, 2, 35, false)
            .withDedicatedCapture(true)
            .withManualMode(true)
            .withShortAudioPackets(true)
            .withShortPacketFrames(44)
            .withWholeDevice(false)
            .withCompression(true)
            .withAdaptiveBuffer(false)
            .withDeviceBuffer(false)
            .withDeviceBufferFrames(928)
            .withReceiverTiming(new ReceiverTiming(true, 45, 50, 35))
            .withShortMixerPeriod(true)
            .withPhoneTiming(new dev.ryuya.homepodbridge.halprobe.PhoneTiming(1, 64))
            .withBuffers(0, 2, 35)
            .withProfile(3);
    assertEquals(44, settings.requestedPacketFrames());
    assertEquals(44, settings.asManual().requestedPacketFrames());
    assertEquals(352, settings.packetFramesForBackend(false));
    assertEquals(352, settings.preset(1).requestedPacketFrames());
    assertEquals(352, settings.withProfile(2).requestedPacketFrames());
    assertThrows(IllegalArgumentException.class, () -> settings.withShortPacketFrames(43));
  }
}
