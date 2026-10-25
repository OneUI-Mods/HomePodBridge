package dev.ryuya.homepodbridge;

import android.content.Intent;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderService;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class HomePodRoutes extends MediaRoute2ProviderService
    implements BridgeState.Observer {
  static final String FEATURE = "dev.ryuya.homepodbridge.AIRPLAY";
  private final Handler main = new Handler(Looper.getMainLooper());
  private Discovery discovery;
  private RoutingSessionInfo session;
  private long pending = REQUEST_ID_NONE;
  private long sequence;
  private boolean creating;
  private boolean destroyed;
  private final Set<String> lastIds = new HashSet<>();

  @Override
  public void onCreate() {
    super.onCreate();
    RootConnector.request(this);
    BridgeState.observe(this);
  }

  @Override
  public void onDiscoveryPreferenceChanged(RouteDiscoveryPreference preference) {
    if (preference.getPreferredFeatures().isEmpty()) {
      if (discovery != null && BridgeState.capture == null) discovery.close();
      return;
    }
    if (discovery == null) {
      discovery =
          new Discovery(
              this,
              new Discovery.Listener() {
                public void changed(java.util.List<Receiver> receivers) {
                  BridgeState.receivers = receivers;
                  BridgeState.publish();
                }

                public void failed() {
                  BridgeState.status(R.string.scan_failed);
                }
              });
    }
    discovery.start();
  }

  @Override
  public void changed() {
    if (destroyed) return;
    ArrayList<MediaRoute2Info> routes = new ArrayList<>();
    lastIds.clear();
    for (Receiver receiver : BridgeState.receivers) {
      lastIds.add(receiver.id());
      routes.add(
          new MediaRoute2Info.Builder(receiver.id(), receiver.name())
              .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
              .addFeature(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK)
              .addFeature(MediaRoute2Info.FEATURE_REMOTE_AUDIO_PLAYBACK)
              .addFeature(FEATURE)
              .setType(MediaRoute2Info.TYPE_REMOTE_SPEAKER)
              .setDescription(getString(R.string.route_description))
              .setConnectionState(
                  BridgeState.connected && receiver.equals(BridgeState.selected)
                      ? MediaRoute2Info.CONNECTION_STATE_CONNECTED
                      : MediaRoute2Info.CONNECTION_STATE_DISCONNECTED)
              .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
              .setVolumeMax(100)
              .setVolume(BridgeState.volume)
              .build());
    }
    notifyRoutes(routes);
    if (session != null && session.getVolume() != BridgeState.volume) {
      session = new RoutingSessionInfo.Builder(session).setVolume(BridgeState.volume).build();
      notifySessionUpdated(session);
    }
    if (session != null
        && (!BridgeState.connected
            || BridgeState.selected == null
            || !lastIds.contains(BridgeState.selected.id()))) {
      RoutingSessionInfo old = session;
      session = null;
      notifySessionReleased(old.getId());
    }
  }

  @Override
  public void onCreateSession(long requestId, String packageName, String routeId, Bundle hints) {
    if (packageName == null || packageName.isEmpty() || BridgeState.PACKAGE.equals(packageName)) {
      notifyRequestFailed(requestId, REASON_REJECTED);
      return;
    }
    Receiver receiver =
        BridgeState.receivers.stream().filter(r -> r.id().equals(routeId)).findFirst().orElse(null);
    if (receiver == null) {
      notifyRequestFailed(requestId, REASON_REJECTED);
      prompt();
      return;
    }
    if (BridgeState.capture == null) {
      RootConnector.request(this);
      if (RootConnector.available()) {
        try {
          startForegroundService(new Intent(this, CaptureService.class).putExtra("system", true));
          main.postDelayed(
              () -> {
                if (destroyed) return;
                if (BridgeState.capture != null)
                  onCreateSession(requestId, packageName, routeId, hints);
                else {
                  notifyRequestFailed(requestId, REASON_REJECTED);
                  prompt();
                }
              },
              200);
          return;
        } catch (RuntimeException ignored) {
        }
      }
      notifyRequestFailed(requestId, REASON_REJECTED);
      prompt();
      return;
    }
    if (pending != REQUEST_ID_NONE) notifyRequestFailed(pending, REASON_REJECTED);
    pending = requestId;
    long token = ++sequence;
    creating = true;
    BridgeState.capture.select(
        receiver,
        packageName,
        success -> {
          if (destroyed || sequence != token) return;
          creating = false;
          pending = REQUEST_ID_NONE;
          if (!success) {
            notifyRequestFailed(requestId, REASON_NETWORK_ERROR);
            return;
          }
          if (session != null) notifySessionReleased(session.getId());
          session =
              new RoutingSessionInfo.Builder("airplay-" + token, packageName)
                  .setName(receiver.name())
                  .addSelectedRoute(routeId)
                  .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                  .setVolumeMax(100)
                  .setVolume(0)
                  .build();
          notifySessionCreated(requestId, session);
          changed();
        });
    main.postDelayed(
        () -> {
          if (destroyed || sequence != token || !creating) return;
          creating = false;
          pending = REQUEST_ID_NONE;
          ++sequence;
          if (BridgeState.capture != null) BridgeState.capture.disconnect();
          notifyRequestFailed(requestId, REASON_NETWORK_ERROR);
        },
        25_000);
  }

  private void prompt() {
    // Avoid background activity launches; the foreground sharing notification opens the app.
    BridgeState.status(R.string.route_not_ready);
  }

  @Override
  public void onReleaseSession(long requestId, String sessionId) {
    if (session == null || !session.getId().equals(sessionId)) {
      notifyRequestFailed(requestId, REASON_INVALID_COMMAND);
      return;
    }
    session = null;
    if (BridgeState.capture != null) BridgeState.capture.disconnect();
    notifySessionReleased(sessionId);
  }

  @Override
  public void onSetRouteVolume(long requestId, String routeId, int volume) {
    if (session == null || !session.getSelectedRoutes().contains(routeId)) {
      notifyRequestFailed(requestId, REASON_INVALID_COMMAND);
      return;
    }
    onSetSessionVolume(requestId, session.getId(), volume);
  }

  @Override
  public void onSetSessionVolume(long requestId, String sessionId, int volume) {
    if (session == null || !session.getId().equals(sessionId)) {
      notifyRequestFailed(requestId, REASON_INVALID_COMMAND);
      return;
    }
    int bounded = VolumePolicy.apply(volume);
    BridgeState.volume = bounded;
    NativeSender.volume(bounded);
    session = new RoutingSessionInfo.Builder(session).setVolume(bounded).build();
    notifySessionUpdated(session);
    changed();
  }

  @Override
  public void onSelectRoute(long requestId, String sessionId, String routeId) {
    notifyRequestFailed(requestId, REASON_INVALID_COMMAND);
  }

  @Override
  public void onDeselectRoute(long requestId, String sessionId, String routeId) {
    notifyRequestFailed(requestId, REASON_INVALID_COMMAND);
  }

  @Override
  public void onTransferToRoute(long requestId, String sessionId, String routeId) {
    notifyRequestFailed(requestId, REASON_INVALID_COMMAND);
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    ++sequence;
    main.removeCallbacksAndMessages(null);
    BridgeState.remove(this);
    if (discovery != null) discovery.close();
    super.onDestroy();
  }
}
