package dev.ryuya.homepodbridge;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import androidx.mediarouter.media.MediaRouteDescriptor;
import androidx.mediarouter.media.MediaRouteDiscoveryRequest;
import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouteProviderDescriptor;
import androidx.mediarouter.media.MediaRouteProviderService;
import androidx.mediarouter.media.MediaRouter;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Routes for applications using the AndroidX Cast-category chooser instead of the system picker.
 */
public final class LegacyCastRoutes extends MediaRouteProviderService {
  private Provider provider;

  @Override
  public MediaRouteProvider onCreateMediaRouteProvider() {
    provider = new Provider(this);
    return provider;
  }

  @Override
  public void onDestroy() {
    if (provider != null) provider.close();
    super.onDestroy();
  }

  private static final class Provider extends MediaRouteProvider implements BridgeState.Observer {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> categories = new LinkedHashSet<>();
    private Discovery discovery;
    private boolean closed;

    Provider(Context context) {
      super(context);
      this.context = context;
      RootConnector.request(context);
      BridgeState.observe(this);
      Diagnostics.route(context, "legacy provider created");
    }

    @Override
    public void onDiscoveryRequestChanged(MediaRouteDiscoveryRequest request) {
      if (request == null) return;
      for (String category : request.getSelector().getControlCategories()) {
        if (category.startsWith("com.google.android.gms.cast.CATEGORY_")) categories.add(category);
      }
      if (categories.isEmpty()) return;
      if (discovery == null)
        discovery =
            new Discovery(
                context,
                new Discovery.Listener() {
                  public void changed(java.util.List<Receiver> receivers) {
                    BridgeState.receivers = receivers;
                    BridgeState.publish();
                  }

                  public void failed() {
                    BridgeState.status(R.string.scan_failed);
                  }
                });
      discovery.start();
      changed();
    }

    @Override
    public void changed() {
      if (closed) return;
      var routes = new MediaRouteProviderDescriptor.Builder();
      for (Receiver receiver : BridgeState.receivers) {
        var route =
            new MediaRouteDescriptor.Builder(receiver.id(), receiver.name())
                .setDescription(context.getString(R.string.route_description))
                .setDeviceType(MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER)
                .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                .setPlaybackStream(AudioManager.STREAM_MUSIC)
                .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(100)
                .setVolume(BridgeState.volume);
        for (String category : categories) {
          IntentFilter filter = new IntentFilter();
          filter.addCategory(category);
          route.addControlFilter(filter);
        }
        routes.addRoute(route.build());
      }
      setDescriptor(routes.build());
    }

    @Override
    public RouteController onCreateRouteController(String routeId) {
      if (BridgeState.receivers.stream().noneMatch(receiver -> receiver.id().equals(routeId)))
        return null;
      return new RouteController() {
        private boolean released;

        @Override
        public void onSelect() {
          Diagnostics.route(context, "legacy select");
          RootConnector.request(context);
          if (!RootConnector.available()) {
            BridgeState.status(R.string.route_not_ready);
            return;
          }
          if (BridgeState.capture == null) {
            try {
              context.startForegroundService(
                  new Intent(context, CaptureService.class).putExtra("system", true));
            } catch (RuntimeException error) {
              BridgeState.status(R.string.route_not_ready);
              return;
            }
          }
          main.postDelayed(() -> select(10), 150);
        }

        private void select(int retries) {
          if (released || closed) return;
          if (BridgeState.capture == null) {
            if (retries > 0) main.postDelayed(() -> select(retries - 1), 150);
            return;
          }
          if (HomePodRoutes.available()) HomePodRoutes.selectFromSystem(BridgeState.MUSIC, routeId);
          else {
            Receiver receiver =
                BridgeState.receivers.stream()
                    .filter(item -> item.id().equals(routeId))
                    .findFirst()
                    .orElse(null);
            if (receiver != null) BridgeState.capture.select(receiver, success -> {});
          }
        }

        @Override
        public void onUnselect(int reason) {
          released = true;
          Diagnostics.route(context, "legacy unselect " + reason);
          if (BridgeState.selected != null && routeId.equals(BridgeState.selected.id())) {
            if (HomePodRoutes.available()) HomePodRoutes.stopFromSystem();
            else if (BridgeState.capture != null) BridgeState.capture.disconnect();
          }
        }

        @Override
        public void onRelease() {
          released = true;
        }

        @Override
        public void onSetVolume(int volume) {
          if (BridgeState.selected == null || !routeId.equals(BridgeState.selected.id())) return;
          BridgeState.volume = VolumePolicy.apply(volume);
          NativeSender.volume(BridgeState.volume);
          BridgeState.publish();
        }

        @Override
        public void onUpdateVolume(int delta) {
          onSetVolume(BridgeState.volume + delta);
        }
      };
    }

    void close() {
      closed = true;
      main.removeCallbacksAndMessages(null);
      BridgeState.remove(this);
      if (discovery != null) discovery.close();
    }
  }
}
