package dev.ryuya.homepodbridge;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MainActivity extends AppCompatActivity implements BridgeState.Observer {
  private TextView status;
  private LinearLayout speakers;
  private Button share;
  private int textColor;
  private int cardColor;
  private Slider volume;
  private TextView volumeLabel;
  private TextView systemStatus;
  private MediaRouter2 router;
  private final MediaRouter2.RouteCallback routes = new MediaRouter2.RouteCallback() {};

  @Override
  public void onCreate(Bundle state) {
    setTheme(R.style.Theme_HomePodBridge);
    DynamicColors.applyToActivityIfAvailable(this);
    super.onCreate(state);
    textColor =
        MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
    cardColor =
        MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceContainer, Color.WHITE);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(
        MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurface, Color.BLACK));
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
    body.addView(card(status));
    systemStatus = label("", 14);
    body.addView(systemStatus);
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
    ((MaterialButton) share)
        .setBackgroundTintList(
            ColorStateList.valueOf(
                MaterialColors.getColor(share, androidx.appcompat.R.attr.colorPrimary)));
    share.setTextColor(
        MaterialColors.getColor(share, com.google.android.material.R.attr.colorOnPrimary));
    ((MaterialButton) share).setStrokeWidth(0);
    body.addView(button(getString(R.string.refresh), view -> refresh()));
    speakers = new LinearLayout(this);
    speakers.setOrientation(LinearLayout.VERTICAL);
    body.addView(speakers);
    LinearLayout volumeControls = new LinearLayout(this);
    volumeControls.setOrientation(LinearLayout.VERTICAL);
    volumeLabel = label(getString(R.string.volume, 0), 18);
    volumeControls.addView(volumeLabel);
    volume = new Slider(this);
    volume.setValueFrom(0);
    volume.setValueTo(BuildConfig.VOLUME_LIMIT);
    volume.setStepSize(1);
    volume.setLabelFormatter(value -> Math.round(value) + "%");
    volume.addOnChangeListener(
        (slider, value, fromUser) -> {
          if (!fromUser || !BridgeState.connected) return;
          BridgeState.volume = VolumePolicy.apply(Math.round(value));
          NativeSender.volume(BridgeState.volume);
          BridgeState.publish();
        });
    volumeControls.addView(volume);
    if (BuildConfig.VOLUME_LIMIT <= 5)
      volumeControls.addView(label(getString(R.string.volume_testing_limit), 14));
    body.addView(card(volumeControls));
    body.addView(
        button(
            getString(R.string.open_music),
            view -> {
              Intent music = getPackageManager().getLaunchIntentForPackage(BridgeState.MUSIC);
              if (music == null) toast(R.string.music_missing);
              else startActivity(music);
            }));
    body.addView(label(getString(R.string.notes), 14));
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
    if (Build.VERSION.SDK_INT >= 37
        && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {"android.permission.ACCESS_LOCAL_NETWORK"}, 4);
      return;
    }
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
    else if (code == 4) requestSharing();
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
    systemStatus.setText(
        RootConnector.available() ? R.string.system_ready : R.string.system_missing);
    volumeLabel.setText(getString(R.string.volume, BridgeState.volume));
    volume.setValue(VolumePolicy.apply(BridgeState.volume));
    volume.setEnabled(BridgeState.connected);
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
    TextViewCompat.setTextAppearance(
        text,
        size >= 28
            ? com.google.android.material.R.style.TextAppearance_Material3_HeadlineLarge
            : size >= 18
                ? com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    text.setTextColor(textColor);
    text.setPadding(0, dp(12), 0, dp(12));
    text.setLineSpacing(dp(3), 1);
    return text;
  }

  private Button button(String label, View.OnClickListener listener) {
    MaterialButton button = new MaterialButton(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextSize(16);
    button.setTextColor(textColor);
    button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
    button.setStrokeWidth(dp(1));
    button.setStrokeColor(
        ColorStateList.valueOf(
            MaterialColors.getColor(button, com.google.android.material.R.attr.colorOutline)));
    button.setPadding(dp(20), dp(12), dp(20), dp(12));
    LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
    layout.setMargins(0, dp(6), 0, dp(6));
    button.setLayoutParams(layout);
    button.setOnClickListener(listener);
    return button;
  }

  private MaterialCardView card(View content) {
    MaterialCardView card = new MaterialCardView(this);
    card.setCardBackgroundColor(cardColor);
    card.setStrokeWidth(0);
    card.setRadius(dp(24));
    card.setContentPadding(dp(20), dp(8), dp(20), dp(8));
    LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
    layout.setMargins(0, dp(8), 0, dp(8));
    card.setLayoutParams(layout);
    card.addView(content);
    return card;
  }

  private void licenses() {
    try (var input = getAssets().open("licenses.txt")) {
      TextView text = label(new String(input.readAllBytes(), StandardCharsets.UTF_8), 13);
      text.setPadding(dp(20), dp(12), dp(20), dp(12));
      ScrollView scroll = new ScrollView(this);
      scroll.addView(text);
      new MaterialAlertDialogBuilder(this)
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
