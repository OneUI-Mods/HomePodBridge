package dev.ryuya.homepodbridge;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Handler;
import android.os.Parcel;
import android.os.RemoteException;

/** A remote volume endpoint owned only while whole-device sharing is connected. */
final class WholeDeviceVolume implements BridgeState.Observer, AutoCloseable {
  private final Context context;
  private final Handler main;
  private MediaSession session;
  private VolumeProvider provider;
  private String receiverName;
  private int lastAudible = 1;
  private final Binder callback =
      new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
          if (code != RootProtocol.ADJUST_VOLUME && code != RootProtocol.SET_VOLUME)
            return super.onTransact(code, data, reply, flags);
          data.enforceInterface(RootProtocol.VOLUME_CALLBACK);
          try {
            if (getCallingUid()
                != context.getPackageManager().getApplicationInfo("com.android.systemui", 0).uid)
              throw new SecurityException("SystemUI only");
          } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
          }
          int value = data.readInt();
          android.util.Log.i(
              "HomePodBridgeVolume", "System request code=" + code + " value=" + value);
          main.post(
              () -> {
                if (code == RootProtocol.ADJUST_VOLUME) {
                  if (session != null && BridgeState.connected)
                    session.getController().adjustVolume(value, AudioManager.FLAG_SHOW_UI);
                } else apply(value);
              });
          return true;
        }
      };

  WholeDeviceVolume(Context context, Handler main) {
    this.context = context;
    this.main = main;
    BridgeState.observe(this);
  }

  @Override
  public void changed() {
    if (!BridgeState.connected
        || BridgeState.selected == null
        || !StreamSettings.load(context).wholeDevice()) {
      releaseSession();
      return;
    }
    if (session == null) {
      provider =
          new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, BridgeState.volume) {
            @Override
            public void onAdjustVolume(int direction) {
              main.post(() -> adjust(direction));
            }

            @Override
            public void onSetVolumeTo(int volume) {
              android.util.Log.i("HomePodBridgeVolume", "Panel set=" + volume);
              main.post(() -> apply(volume));
            }
          };
      session = new MediaSession(context, "HomePod Bridge volume");
      session.setCallback(new MediaSession.Callback() {}, main);
      session.setPlaybackToRemote(provider);
      session.setPlaybackState(
          new PlaybackState.Builder()
              .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
              .build());
      session.setActive(true);
      if (BridgeState.capture != null)
        RootConnector.volumeEndpoint(BridgeState.capture.lifetime(), callback);
    }
    if (!BridgeState.selected.name().equals(receiverName)) {
      receiverName = BridgeState.selected.name();
      session.setMetadata(
          new MediaMetadata.Builder()
              .putString(MediaMetadata.METADATA_KEY_TITLE, receiverName)
              .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, receiverName)
              .putString(MediaMetadata.METADATA_KEY_ARTIST, context.getString(R.string.app_name))
              .build());
    }
    if (BridgeState.volume > 0) lastAudible = BridgeState.volume;
    if (provider.getCurrentVolume() != BridgeState.volume)
      provider.setCurrentVolume(BridgeState.volume);
  }

  private void adjust(int direction) {
    int target =
        switch (direction) {
          case AudioManager.ADJUST_RAISE -> BridgeState.volume + 1;
          case AudioManager.ADJUST_LOWER -> BridgeState.volume - 1;
          case AudioManager.ADJUST_MUTE -> 0;
          case AudioManager.ADJUST_UNMUTE -> lastAudible;
          case AudioManager.ADJUST_TOGGLE_MUTE -> BridgeState.volume == 0 ? lastAudible : 0;
          default -> BridgeState.volume;
        };
    apply(target);
  }

  private void apply(int requested) {
    if (session == null || !BridgeState.connected || !StreamSettings.load(context).wholeDevice())
      return;
    int bounded = VolumePolicy.apply(requested);
    android.util.Log.i("HomePodBridgeVolume", "Apply=" + bounded);
    if (bounded == BridgeState.volume) return;
    BridgeState.volume = bounded;
    NativeSender.volume(bounded);
    BridgeState.publish();
  }

  private void releaseSession() {
    if (session == null) return;
    session.setActive(false);
    session.release();
    session = null;
    provider = null;
    receiverName = null;
  }

  @Override
  public void close() {
    BridgeState.remove(this);
    releaseSession();
  }
}
