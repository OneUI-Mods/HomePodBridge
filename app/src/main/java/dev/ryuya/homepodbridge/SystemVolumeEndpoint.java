package dev.ryuya.homepodbridge;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.Executor;

/** A lease on the unused speaker's media-volume commands, owned by one capture stream. */
final class SystemVolumeEndpoint implements AutoCloseable {
  private final AudioManager audio;
  private final Object manager;
  private final Object device;
  private final Object previousVolume;
  private final HandlerThread thread = new HandlerThread("HomePod-volume");
  private final Handler handler;
  private final IBinder callback;
  private final Class<?> infoBuilder;
  private volatile boolean closed;
  private boolean registered;
  private volatile int current;

  SystemVolumeEndpoint(Context context, IBinder callback, int volume) {
    this.callback = callback;
    audio = context.getSystemService(AudioManager.class);
    ClassLoader loader = context.getClassLoader();
    Class<?> managerClass =
        XposedHelpers.findClass("android.media.AudioDeviceVolumeManager", loader);
    manager = XposedHelpers.newInstance(managerClass, context);
    device =
        XposedHelpers.newInstance(
            XposedHelpers.findClass("android.media.AudioDeviceAttributes", loader), 2, 2, "");
    // Preserve other absolute-volume owners and nonstandard device behaviors.
    if ((Integer) XposedHelpers.callMethod(manager, "getDeviceVolumeBehavior", device) != 0)
      throw new IllegalStateException("Speaker already has a volume owner");
    infoBuilder = XposedHelpers.findClass("android.media.VolumeInfo$Builder", loader);
    Object query =
        XposedHelpers.callMethod(
            XposedHelpers.newInstance(infoBuilder, AudioManager.STREAM_MUSIC), "build");
    previousVolume = XposedHelpers.callMethod(manager, "getDeviceVolume", query, device);
    current = VolumePolicy.apply(volume);
    thread.start();
    handler = new Handler(thread.getLooper());
    try {
      Class<?> listenerClass =
          XposedHelpers.findClass(
              "android.media.AudioDeviceVolumeManager$OnAudioDeviceVolumeChangedListener", loader);
      Object listener =
          Proxy.newProxyInstance(
              loader,
              new Class<?>[] {listenerClass},
              (proxy, method, args) -> {
                try {
                  if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                  if (method.getName().equals("equals")) return proxy == args[0];
                  if (method.getName().equals("toString")) return "HomePod volume lease";
                  if (closed || args == null || args.length < 2) return null;
                  int stream = (Integer) XposedHelpers.callMethod(args[1], "getStreamType");
                  boolean media =
                      stream == AudioManager.STREAM_MUSIC
                          && audio.getMode() == AudioManager.MODE_NORMAL;
                  if (method.getName().equals("onAudioDeviceVolumeAdjusted")) {
                    int direction = (Integer) args[2], mode = (Integer) args[3];
                    if (mode == 2 || direction == AudioManager.ADJUST_SAME) return null;
                    if (media) send(RootProtocol.ADJUST_VOLUME, direction);
                    else {
                      // Bypass our absolute-volume callback for normal non-media/call controls.
                      int bypass =
                          XposedHelpers.getStaticIntField(
                              AudioManager.class, "FLAG_ABSOLUTE_VOLUME");
                      audio.adjustStreamVolume(
                          stream, direction, bypass | AudioManager.FLAG_SHOW_UI);
                    }
                  } else if (method.getName().equals("onAudioDeviceVolumeChanged") && media) {
                    send(
                        RootProtocol.SET_VOLUME,
                        (Integer) XposedHelpers.callMethod(args[1], "getVolumeIndex"));
                  }
                  return null;
                } catch (Throwable error) {
                  close();
                  return null;
                }
              });
      ArrayList<Object> volumes = new ArrayList<>();
      int streams =
          (Integer)
              XposedHelpers.callStaticMethod(
                  XposedHelpers.findClass("android.media.AudioSystem", loader),
                  "getNumStreamTypes");
      for (int stream = 0; stream < streams; stream++) {
        Object builder = XposedHelpers.newInstance(infoBuilder, stream);
        XposedHelpers.callMethod(
            builder,
            "setMinVolumeIndex",
            stream == AudioManager.STREAM_MUSIC ? 0 : audio.getStreamMinVolume(stream));
        XposedHelpers.callMethod(
            builder,
            "setMaxVolumeIndex",
            stream == AudioManager.STREAM_MUSIC ? 100 : audio.getStreamMaxVolume(stream));
        // Other streams keep their current state and pass commands through unchanged.
        if (stream == AudioManager.STREAM_MUSIC)
          XposedHelpers.callMethod(builder, "setVolumeIndex", current);
        volumes.add(XposedHelpers.callMethod(builder, "build"));
      }
      Executor executor = command -> handler.post(command);
      registered = true;
      XposedHelpers.callMethod(
          manager,
          "setDeviceAbsoluteMultiVolumeBehavior",
          device,
          volumes,
          true,
          executor,
          listener);
    } catch (Throwable error) {
      close();
      throw error;
    }
  }

  private void send(int code, int value) {
    if (closed) return;
    Parcel data = Parcel.obtain();
    try {
      data.writeInterfaceToken(RootProtocol.VOLUME_CALLBACK);
      data.writeInt(value);
      callback.transact(code, data, null, IBinder.FLAG_ONEWAY);
    } catch (android.os.RemoteException e) {
      close();
    } finally {
      data.recycle();
    }
  }

  void update(int volume) {
    int bounded = VolumePolicy.apply(volume);
    if (current == bounded || closed) return;
    current = bounded;
    handler.post(
        () -> {
          try {
            if (closed) return;
            Object builder = XposedHelpers.newInstance(infoBuilder, AudioManager.STREAM_MUSIC);
            XposedHelpers.callMethod(builder, "setMinVolumeIndex", 0);
            XposedHelpers.callMethod(builder, "setMaxVolumeIndex", 100);
            XposedHelpers.callMethod(builder, "setVolumeIndex", current);
            XposedHelpers.callMethod(
                manager,
                "notifyAbsoluteVolumeChanged",
                XposedHelpers.callMethod(builder, "build"),
                device);
          } catch (Throwable error) {
            close();
          }
        });
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (registered) {
      try {
        if ((Integer) XposedHelpers.callMethod(manager, "getDeviceVolumeBehavior", device) == 3) {
          XposedHelpers.callMethod(manager, "resetDeviceAbsoluteVolumeBehavior", device);
          XposedHelpers.callMethod(manager, "setDeviceVolume", previousVolume, device);
        }
      } catch (Throwable ignored) {
      }
    }
    thread.quitSafely();
  }
}
