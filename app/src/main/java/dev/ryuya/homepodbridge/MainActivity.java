package dev.ryuya.homepodbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRouter2;
import android.media.RouteDiscoveryPreference;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MainActivity extends Activity implements BridgeState.Observer {
  private TextView status;
  private LinearLayout speakers;
  private Button share;
  private int textColor;
  private int cardColor;
  private MediaRouter2 router;
  private final MediaRouter2.RouteCallback routes = new MediaRouter2.RouteCallback() {};

  @Override
  public void onCreate(Bundle state) {
    boolean dark =
        (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    setTheme(
        dark
            ? android.R.style.Theme_Material_NoActionBar
            : android.R.style.Theme_Material_Light_NoActionBar);
    super.onCreate(state);
    textColor = dark ? 0xfff2f3f5 : 0xff17191d;
    cardColor = dark ? 0xff202227 : Color.WHITE;
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(dark ? 0xff101114 : 0xfff3f4f7);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(24), dp(24), dp(24), dp(32));
    scroll.addView(body);
    scroll.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          android.graphics.Insets bars =
              insets.getInsets(android.view.WindowInsets.Type.systemBars());
          body.setPadding(
              dp(24) + bars.left, dp(24) + bars.top, dp(24) + bars.right, dp(32) + bars.bottom);
          return insets;
        });
    TextView title = label(getString(R.string.intro), 30);
    title.setTypeface(null, Typeface.BOLD);
    body.addView(title);
    body.addView(label(getString(R.string.explanation), 16));
    status = label(getString(R.string.idle), 18);
    body.addView(status);
    share =
        button(
            getString(R.string.enable),
            view -> {
              if (BridgeState.capture != null) {
                stopService(new Intent(this, CaptureService.class));
                return;
              }
              requestSharing();
            });
    body.addView(share);
    body.addView(button(getString(R.string.refresh), view -> refresh()));
    speakers = new LinearLayout(this);
    speakers.setOrientation(LinearLayout.VERTICAL);
    body.addView(speakers);
    body.addView(
        button(
            getString(R.string.open_music),
            view -> {
              Intent music = getPackageManager().getLaunchIntentForPackage(BridgeState.MUSIC);
              if (music == null) toast(R.string.music_missing);
              else startActivity(music);
            }));
    body.addView(label(getString(R.string.notes), 14));
    body.addView(label(getString(R.string.unverified), 14));
    body.addView(button(getString(R.string.licenses), view -> licenses()));
    setContentView(scroll);
  }

  @Override
  protected void onStart() {
    super.onStart();
    RootConnector.request(this);
    BridgeState.observe(this);
    if (checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")
            == PackageManager.PERMISSION_GRANTED
        || Build.VERSION.SDK_INT < 37) refresh();
  }

  @Override
  protected void onStop() {
    BridgeState.remove(this);
    if (router != null) {
      router.unregisterRouteCallback(routes);
      router = null;
    }
    super.onStop();
  }

  private void refresh() {
    if (Build.VERSION.SDK_INT >= 37
        && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {"android.permission.ACCESS_LOCAL_NETWORK"}, 3);
      return;
    }
    if (router == null) {
      router = MediaRouter2.getInstance(this);
      router.registerRouteCallback(
          getMainExecutor(),
          routes,
          new RouteDiscoveryPreference.Builder(List.of(HomePodRoutes.FEATURE), true).build());
    }
    if (BridgeState.receivers.isEmpty()) {
      BridgeState.status = R.string.searching;
      changed();
    }
  }

  private void requestSharing() {
    if (RootConnector.available()) {
      startForegroundService(new Intent(this, CaptureService.class).putExtra("system", true));
      return;
    }
    try {
      getPackageManager().getApplicationInfo(BridgeState.MUSIC, 0);
    } catch (PackageManager.NameNotFoundException e) {
      toast(R.string.music_missing);
      return;
    }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(
          new String[] {Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS},
          2);
      return;
    }
    MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
    startActivityForResult(
        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()),
        1);
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] granted) {
    super.onRequestPermissionsResult(code, permissions, granted);
    if (granted.length == 0 || granted[0] != PackageManager.PERMISSION_GRANTED) {
      toast(R.string.permission_needed);
      return;
    }
    if (code == 2) requestSharing();
    else if (code == 3) refresh();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != 1 || resultCode != RESULT_OK || data == null) return;
    startForegroundService(
        new Intent(this, CaptureService.class)
            .putExtra("consent", data)
            .putExtra("result", resultCode));
  }

  @Override
  public void changed() {
    if (status == null) return;
    String message;
    if (BridgeState.selected != null && BridgeState.status == R.string.connecting)
      message = getString(R.string.connecting, BridgeState.selected.name());
    else if (BridgeState.selected != null && BridgeState.status == R.string.playing)
      message = getString(R.string.playing, BridgeState.selected.name());
    else message = getString(BridgeState.status);
    status.setText(message);
    share.setText(BridgeState.capture == null ? R.string.enable : R.string.stop);
    speakers.removeAllViews();
    for (Receiver receiver : BridgeState.receivers) {
      Button speaker =
          button(
              receiver.name(),
              view -> {
                if (BridgeState.capture == null) {
                  toast(R.string.route_not_ready);
                  requestSharing();
                  return;
                }
                BridgeState.capture.select(
                    receiver,
                    success -> {
                      if (!success) toast(R.string.connection_failed);
                    });
              });
      speaker.setSelected(receiver.equals(BridgeState.selected));
      speakers.addView(speaker);
    }
    if (!BridgeState.receivers.isEmpty() && BridgeState.status == R.string.searching)
      status.setText(R.string.ready);
  }

  private TextView label(String value, int size) {
    TextView text = new TextView(this);
    text.setText(value);
    text.setTextSize(size);
    text.setTextColor(textColor);
    text.setPadding(0, dp(12), 0, dp(12));
    text.setLineSpacing(dp(3), 1);
    return text;
  }

  private Button button(String label, View.OnClickListener listener) {
    Button button = new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextSize(16);
    button.setTextColor(textColor);
    GradientDrawable background = new GradientDrawable();
    background.setColor(cardColor);
    background.setCornerRadius(dp(22));
    button.setBackground(background);
    button.setPadding(dp(20), dp(12), dp(20), dp(12));
    LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
    layout.setMargins(0, dp(6), 0, dp(6));
    button.setLayoutParams(layout);
    button.setOnClickListener(listener);
    return button;
  }

  private void licenses() {
    try (var input = getAssets().open("licenses.txt")) {
      TextView text = label(new String(input.readAllBytes(), StandardCharsets.UTF_8), 13);
      text.setPadding(dp(20), dp(12), dp(20), dp(12));
      ScrollView scroll = new ScrollView(this);
      scroll.addView(text);
      new AlertDialog.Builder(this)
          .setTitle(R.string.licenses)
          .setView(scroll)
          .setPositiveButton(android.R.string.ok, null)
          .show();
    } catch (java.io.IOException e) {
      toast(R.string.licenses);
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void toast(int string) {
    Toast.makeText(this, string, Toast.LENGTH_LONG).show();
  }
}
