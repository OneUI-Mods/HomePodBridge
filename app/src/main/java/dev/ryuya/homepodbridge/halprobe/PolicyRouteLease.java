package dev.ryuya.homepodbridge.halprobe;

import android.content.Context;
import android.media.AudioManager;

/** A connection for one currently registered self-UID virtual mix; never a physical device. */
final class PolicyRouteLease implements AutoCloseable {
  private final Class<?> system, attributesType;
  private final Object attributes;
  private final String address;
  private boolean attempted;

  static PolicyRouteLease open(Context context, String address) throws Exception {
    PolicyRouteLease lease = new PolicyRouteLease(context, address);
    try {
      lease.attempted = true;
      if (lease.change(1) != 0 || lease.state() != 1)
        throw new IllegalStateException("Owned virtual output connection refused");
      return lease;
    } catch (Exception failure) {
      lease.close();
      throw failure;
    }
  }

  private PolicyRouteLease(Context context, String address) throws Exception {
    if (android.os.Process.myUid() != 0
        || address == null
        || !address.matches("[0-9]+:ap:[0-9]+mixp:[0-9]+"))
      throw new SecurityException("Owned root policy registration required");
    this.address = address;
    boolean input = false;
    for (var device :
        context.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS))
      if (device.isSource()
          && address.equals(device.getAddress())
          && device.getType() == android.media.AudioDeviceInfo.TYPE_REMOTE_SUBMIX) input = true;
    if (!input) throw new SecurityException("Owned policy input missing");
    system = Class.forName("android.media.AudioSystem");
    attributesType = Class.forName("android.media.AudioDeviceAttributes");
    // The native type is fixed AUDIO_DEVICE_OUT_REMOTE_SUBMIX, never supplied by a caller.
    attributes =
        attributesType
            .getConstructor(int.class, String.class, String.class)
            .newInstance(0x8000, address, "HomePod Bridge private capture");
    if (state() != 0) throw new SecurityException("Virtual output already connected");
  }

  private int state() throws Exception {
    return (Integer)
        system
            .getMethod("getDeviceConnectionState", int.class, String.class)
            .invoke(null, 0x8000, address);
  }

  private int change(int value) throws Exception {
    return (Integer)
        system
            .getMethod("setDeviceConnectionState", attributesType, int.class, int.class)
            .invoke(null, attributes, value, 0);
  }

  @Override
  public void close() throws Exception {
    if (!attempted) return;
    if (state() != 0 && change(0) != 0)
      throw new IllegalStateException("Owned virtual output disconnect refused");
    if (state() != 0) throw new IllegalStateException("Virtual output still connected");
    attempted = false;
  }
}
