package dev.ryuya.homepodbridge.halprobe;

import android.content.Context;
import android.media.AudioManager;
import android.os.IBinder;

/** Routing input claim without an AudioRecord/RecordTrack consumer. Always source8 + exact mix. */
final class PolicyInputLease implements AutoCloseable {
  private final Object service;
  private final Class<?> api;
  private int port;
  private boolean started;
  final int input;

  static PolicyInputLease open(Context context, String address) throws Exception {
    return new PolicyInputLease(context, address);
  }

  private PolicyInputLease(Context context, String address) throws Exception {
    if (address == null || !address.matches("[0-9]+:ap:[0-9]+mixp:[0-9]+"))
      throw new SecurityException("Unrecognized policy address");
    api = Class.forName("android.media.IAudioPolicyService");
    IBinder binder =
        (IBinder)
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "media.audio_policy");
    if (binder == null || !api.getName().equals(binder.getInterfaceDescriptor()))
      throw new SecurityException("Policy service unavailable");
    service =
        Class.forName(api.getName() + "$Stub")
            .getMethod("asInterface", IBinder.class)
            .invoke(null, binder);
    Class<?> attrType = Class.forName("android.media.audio.common.AudioAttributes");
    Object attributes = attrType.getConstructor().newInstance();
    attrType.getField("source").setInt(attributes, 8);
    attrType.getField("tags").set(attributes, new String[] {"addr=" + address, "fixedVolume"});
    Class<?> configType = Class.forName("android.media.audio.common.AudioConfigBase");
    Object config = configType.getConstructor().newInstance();
    configType.getField("sampleRate").setInt(config, 44100);
    Class<?> layout = Class.forName("android.media.audio.common.AudioChannelLayout");
    configType
        .getField("channelMask")
        .set(config, layout.getMethod("layoutMask", int.class).invoke(null, 3));
    Class<?> formatType = Class.forName("android.media.audio.common.AudioFormatDescription");
    Object format = formatType.getConstructor().newInstance();
    formatType.getField("type").setByte(format, (byte) 1);
    formatType.getField("pcm").setByte(format, (byte) 1);
    formatType.getField("encoding").set(format, "");
    configType.getField("format").set(config, format);
    Object attribution =
        context
            .getAttributionSource()
            .getClass()
            .getMethod("asState")
            .invoke(context.getAttributionSource());
    int session = context.getSystemService(AudioManager.class).generateAudioSessionId();
    if (session <= 0) throw new IllegalStateException("No audio session");
    Object response =
        api.getMethod(
                "getInputForAttr",
                attrType,
                int.class,
                int.class,
                int.class,
                attribution.getClass(),
                configType,
                int.class,
                int.class)
            .invoke(service, attributes, 0, -1, session, attribution, config, 0, 0);
    port = response.getClass().getField("portId").getInt(response);
    input = response.getClass().getField("input").getInt(response);
    try {
      if (port <= 0 || input <= 0 || response.getClass().getField("source").getInt(response) != 8)
        throw new SecurityException("Unexpected input source");
      int selected = response.getClass().getField("selectedDeviceId").getInt(response);
      boolean matched = false;
      for (var device :
          context.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS))
        if (device.getId() == selected && device.isSource() && address.equals(device.getAddress()))
          matched = true;
      if (!matched) throw new SecurityException("Input did not select the owned virtual mix");
      api.getMethod("startInput", int.class).invoke(service, port);
      started = true;
    } catch (Exception failure) {
      close();
      throw failure;
    }
  }

  @Override
  public void close() throws Exception {
    if (port == 0) return;
    int owned = port;
    port = 0;
    try {
      if (started) api.getMethod("stopInput", int.class).invoke(service, owned);
    } finally {
      api.getMethod("releaseInput", int.class).invoke(service, owned);
      started = false;
    }
  }
}
