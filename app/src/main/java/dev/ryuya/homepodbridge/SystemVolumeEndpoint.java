package dev.ryuya.homepodbridge;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
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
  private final MediaSessionManager mediaSessions;
  private final MediaController remoteController;
  private final VolumeKeyChanges volumeKeys;
  private final RemoteVolumeIcons volumeIcons = new RemoteVolumeIcons();
  private final int previousFineVolume;
  private boolean drainScheduled;
  private boolean iconRegistered;
  private volatile boolean panelErrorLogged;
  private volatile boolean closed;
  private boolean registered;

  SystemVolumeEndpoint(Context context, IBinder callback) {
    this.callback = callback;
    if (!SystemVolumeUi.available)
      throw new IllegalStateException("Remote volume panel not available");
    audio = context.getSystemService(AudioManager.class);
    mediaSessions = context.getSystemService(MediaSessionManager.class);
    remoteController =
        mediaSessions.getActiveSessions(null).stream()
            .filter(session -> RootProtocol.PACKAGE.equals(session.getPackageName()))
            .filter(
                session ->
                    session.getPlaybackInfo() != null
                        && session.getPlaybackInfo().getPlaybackType() == 2)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Owned remote session unavailable"));
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
    int fine = -1;
    int baseline = (Integer) XposedHelpers.callMethod(previousVolume, "getVolumeIndex");
    int maximum = (Integer) XposedHelpers.callMethod(previousVolume, "getMaxVolumeIndex");
    baseline = maximum > 0 ? baseline * 100 / maximum : 0;
    try {
      fine =
          (Integer)
              XposedHelpers.callMethod(audio, "semGetFineVolume", AudioManager.STREAM_MUSIC, 2);
      baseline =
          Math.round(fine * 100f / (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 10));
    } catch (Throwable ignored) {
    }
    previousFineVolume = fine;
    volumeKeys = new VolumeKeyChanges(baseline);
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
                    volumeKeys.changed(
                        (Integer) XposedHelpers.callMethod(args[1], "getVolumeIndex"),
                        android.os.SystemClock.uptimeMillis());
                    restoreLocalVolume();
                    scheduleKeys();
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
        if (!(Boolean)
            XposedHelpers.callStaticMethod(AudioManager.class, "isVolumeControlStreamType", stream))
          continue;
        Object builder = XposedHelpers.newInstance(infoBuilder, stream);
        XposedHelpers.callMethod(
            builder,
            "setMinVolumeIndex",
            stream == AudioManager.STREAM_MUSIC ? 0 : audio.getStreamMinVolume(stream));
        XposedHelpers.callMethod(
            builder,
            "setMaxVolumeIndex",
            stream == AudioManager.STREAM_MUSIC ? 100 : audio.getStreamMaxVolume(stream));
        // Every index stays unset: registration must not alter local volumes.
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
      SystemVolumeUi.active = this;
    } catch (Throwable error) {
      close();
      throw error;
    }
  }

  private boolean ownsSession(Object sessionId) {
    if (closed) return false;
    try {
      Object token = sessionId;
      if (!(token instanceof android.media.session.MediaSession.Token))
        token = XposedHelpers.getObjectField(sessionId, "token");
      return remoteController.getSessionToken().equals(token);
    } catch (Throwable ignored) {
      return false;
    }
  }

  void rememberRemoteStream(Object callbacks, Object sessionId) {
    if (!ownsSession(sessionId)) return;
    try {
      Object streams = XposedHelpers.getObjectField(callbacks, "mRemoteStreams");
      synchronized (streams) {
        if (streams instanceof Map<?, ?> map && map.get(sessionId) instanceof Integer stream) {
          volumeIcons.stream = stream;
          if (!iconRegistered) {
            iconRegistered = true;
            android.util.Log.i("HomePodBridgeUi", "Owned volume panel registered");
          }
        }
      }
    } catch (Throwable ignored) {
    }
  }

  Object handleIconAction(Object action, Object state) {
    if (closed || state == null) return null;
    try {
      Object type = XposedHelpers.callMethod(action, "getActionType");
      if (!(type instanceof Enum<?> value) || !value.name().equals("ACTION_VOLUME_ICON_CLICKED"))
        return null;
      int stream = (Integer) XposedHelpers.callMethod(action, "getStream");
      if (!volumeIcons.owns(stream)) return null;
      var rows = (java.util.List<?>) XposedHelpers.callMethod(state, "getVolumeRowList");
      for (Object row : rows) {
        if ((Integer) XposedHelpers.callMethod(row, "getStreamType") != stream) continue;
        Integer target =
            volumeIcons.target(stream, (Integer) XposedHelpers.callMethod(row, "getLevel"));
        if (target == null) return null;
        Object builder =
            XposedHelpers.newInstance(
                XposedHelpers.findClass(
                    state.getClass().getName() + "$Builder", state.getClass().getClassLoader()),
                state);
        Object noDispatch =
            XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass(
                    state.getClass().getName() + "$StateType", state.getClass().getClassLoader()),
                "STATE_NO_DISPATCH");
        XposedHelpers.callMethod(builder, "setStateType", noDispatch);
        Object result = XposedHelpers.callMethod(builder, "build");
        handler.post(() -> send(RootProtocol.SET_VOLUME, target));
        android.util.Log.i("HomePodBridgeUi", "Owned volume icon target=" + target);
        return result;
      }
    } catch (Throwable error) {
      android.util.Log.w(
          "HomePodBridgeUi", "Owned icon action unavailable: " + error.getClass().getSimpleName());
    }
    return null;
  }

  Object enableIconState(Object state) {
    if (closed || state == null) return state;
    try {
      Object rows = XposedHelpers.callMethod(state, "getVolumeRowList");
      Object enabled = enableOwnedIcon(rows);
      if (enabled == rows) return state;
      Object builder =
          XposedHelpers.newInstance(
              XposedHelpers.findClass(
                  state.getClass().getName() + "$Builder", state.getClass().getClassLoader()),
              state);
      XposedHelpers.callMethod(builder, "setVolumeRowList", enabled);
      return XposedHelpers.callMethod(builder, "build");
    } catch (Throwable ignored) {
      return state;
    }
  }

  Object enableOwnedIcon(Object rows) {
    if (closed || !(rows instanceof java.util.List<?> list)) return rows;
    try {
      ArrayList<Object> updated = new ArrayList<>(list);
      for (int i = 0; i < updated.size(); i++) {
        Object row = updated.get(i);
        int stream = (Integer) XposedHelpers.callMethod(row, "getStreamType");
        if (!volumeIcons.owns(stream)) continue;
        Class<?> builder =
            XposedHelpers.findClass(
                row.getClass().getName() + "$Builder", row.getClass().getClassLoader());
        Object copy = XposedHelpers.newInstance(builder, row);
        XposedHelpers.callMethod(copy, "isIconClickable", true);
        updated.set(i, XposedHelpers.callMethod(copy, "build"));
      }
      return updated;
    } catch (Throwable ignored) {
      return rows;
    }
  }

  String receiverNameFor(Object sessionId) {
    if (!ownsSession(sessionId)) return null;
    try {
      MediaMetadata metadata = remoteController.getMetadata();
      return metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
    } catch (Throwable ignored) {
      return null;
    }
  }

  boolean redirectLocalUi(Object controller, int flags) {
    if (closed || audio.getMode() != AudioManager.MODE_NORMAL) return false;
    try {
      Object sessions = XposedHelpers.getObjectField(controller, "mMediaSessions");
      Object token = remoteController.getSessionToken();
      Object records = XposedHelpers.getObjectField(sessions, "mRecords");
      if (records instanceof Map<?, ?> map && !map.containsKey(token)) {
        try {
          XposedHelpers.callStaticMethod(
              sessions.getClass(),
              "access$onActiveSessionsUpdatedH",
              sessions,
              mediaSessions.getActiveSessions(null));
        } catch (Throwable ignored) {
          XposedHelpers.callMethod(
              sessions, "onActiveSessionsUpdatedH", mediaSessions.getActiveSessions(null));
        }
        if (!map.containsKey(token)) return false;
      }
      MediaMetadata metadata = remoteController.getMetadata();
      String name =
          metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
      if (name == null) name = "HomePod Bridge";
      XposedHelpers.callMethod(
          sessions, "updateRemoteH", token, name, remoteController.getPlaybackInfo());
      Object callbacks = XposedHelpers.getObjectField(controller, "mMediaSessionsCallbacksW");
      Class<?> idClass =
          XposedHelpers.findClassIfExists(
              "com.android.settingslib.volume.MediaSessions$SessionId$Media",
              controller.getClass().getClassLoader());
      Object id = idClass == null ? token : XposedHelpers.newInstance(idClass, token);
      XposedHelpers.callMethod(callbacks, "onRemoteVolumeChanged", id, flags);
      int fromKey = XposedHelpers.getStaticIntField(AudioManager.class, "FLAG_FROM_KEY");
      if ((flags & (fromKey | AudioManager.FLAG_SHOW_UI))
          == (fromKey | AudioManager.FLAG_SHOW_UI)) {
        long now = android.os.SystemClock.uptimeMillis();
        handler.post(
            () -> {
              if (!closed) {
                volumeKeys.key(now);
                scheduleKeys();
              }
            });
      }
      return true;
    } catch (Throwable error) {
      if (!panelErrorLogged) {
        panelErrorLogged = true;
        de.robv.android.xposed.XposedBridge.log("HomePod Bridge: remote volume panel failed");
        de.robv.android.xposed.XposedBridge.log(error);
      }
      return false; // Preserve the ROM's panel when its internals differ.
    }
  }

  private void restoreLocalVolume() {
    if (previousFineVolume >= 0) {
      int now =
          (Integer)
              XposedHelpers.callMethod(audio, "semGetFineVolume", AudioManager.STREAM_MUSIC, 2);
      if (now == previousFineVolume) return;
      int bypass = XposedHelpers.getStaticIntField(AudioManager.class, "FLAG_ABSOLUTE_VOLUME");
      XposedHelpers.callMethod(
          audio, "semSetFineVolume", AudioManager.STREAM_MUSIC, previousFineVolume, bypass, 2);
    } else {
      Object now = XposedHelpers.callMethod(manager, "getDeviceVolume", previousVolume, device);
      if (XposedHelpers.callMethod(now, "getVolumeIndex")
          .equals(XposedHelpers.callMethod(previousVolume, "getVolumeIndex"))) return;
      XposedHelpers.callMethod(manager, "notifyAbsoluteVolumeChanged", previousVolume, device);
    }
  }

  private void scheduleKeys() {
    if (closed || drainScheduled) return;
    drainScheduled = true;
    handler.postDelayed(
        () -> {
          drainScheduled = false;
          if (closed) return;
          for (int direction : volumeKeys.drain(android.os.SystemClock.uptimeMillis()))
            send(RootProtocol.ADJUST_VOLUME, direction);
          if (volumeKeys.pendingKeys()) scheduleKeys();
        },
        30);
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

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (SystemVolumeUi.active == this) SystemVolumeUi.active = null;
    if (registered) {
      try {
        if ((Integer) XposedHelpers.callMethod(manager, "getDeviceVolumeBehavior", device) == 3) {
          XposedHelpers.callMethod(manager, "resetDeviceAbsoluteVolumeBehavior", device);
          if (previousFineVolume >= 0)
            XposedHelpers.callMethod(
                audio, "semSetFineVolume", AudioManager.STREAM_MUSIC, previousFineVolume, 0, 2);
          else XposedHelpers.callMethod(manager, "setDeviceVolume", previousVolume, device);
        }
      } catch (Throwable ignored) {
      }
    }
    thread.quitSafely();
  }
}
