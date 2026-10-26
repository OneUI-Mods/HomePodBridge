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
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MainActivity extends AppCompatActivity implements BridgeState.Observer {
  private TextView status;
  private TextView timingStatus;
  private TextView captureTiming;
  private TextView pipeTiming;
  private TextView senderTiming;
  private TextView handoffTiming;
  private TextView networkTiming;
  private TextView phoneBufferTiming;
  private LinearLayout speakers;
  private Button share;
  private int textColor;
  private int cardColor;
  private Slider volume;
  private Button exactVolume;
  private TextView volumeLabel;
  private TextView systemStatus;
  private TextView modeSelection;
  private MaterialSwitch lowLatencySwitch;
  private MaterialSwitch highQualitySwitch;
  private MaterialSwitch compressionSwitch;
  private MaterialSwitch adaptiveBufferSwitch;
  private MaterialSwitch deviceBufferSwitch;
  private MaterialSwitch minimumBufferSwitch;
  private MaterialSwitch dedicatedCaptureSwitch;
  private TextView captureBackendStatus;
  private TextView profileSelection;
  private TextView profileDetails;
  private TabLayout tabs;
  private boolean updatingSettings;
  private MediaRouter2 router;
  private final MediaRouter2.RouteCallback routes = new MediaRouter2.RouteCallback() {};

  @Override
  public void dump(
      String prefix, java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
    if (args != null && args.length == 1 && args[0].equals("--measure-capture-tuned")) {
      CaptureProbe.request(this, 2);
      CaptureProbe.dump(writer);
      return;
    }
    if (args != null && args.length == 1 && args[0].equals("--measure-capture-guard")) {
      CaptureProbe.request(this, true);
      CaptureProbe.dump(writer);
      return;
    }
    if (args != null && args.length == 1 && args[0].equals("--measure-capture")) {
      CaptureProbe.request(this);
      CaptureProbe.dump(writer);
      return;
    }
    if (args != null && args.length == 1 && args[0].equals("--capture-probe-status")) {
      CaptureProbe.dump(writer);
      HalInspection.dump(writer, false);
      return;
    }
    if (args != null && args.length == 1 && args[0].equals("--inspect-dedicated-route")) {
      HalInspection.requestRoute(this, BridgeState.captureRegistration);
      HalInspection.dump(writer, false);
      return;
    }
    if (args != null && args.length > 0 && args[0].equals("--inspect-dedicated-hal")) {
      if (args.length == 2 && args[1].matches("[0-2]"))
        HalInspection.request(Integer.parseInt(args[1]));
      HalInspection.dump(writer, false);
      return;
    }
    if (args != null && args.length == 1 && args[0].equals("--dedicated-hal-image")) {
      HalInspection.dump(writer, true);
      return;
    }
    super.dump(prefix, fd, writer, args);
  }

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
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(
        MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurface, Color.BLACK));
    root.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          android.graphics.Insets bars =
              insets.getInsets(android.view.WindowInsets.Type.systemBars());
          root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    TextView title = label(getString(R.string.intro), 30);
    title.setTypeface(null, Typeface.BOLD);
    title.setPadding(dp(24), dp(16), dp(24), dp(8));
    root.addView(title);
    tabs = new TabLayout(this);
    tabs.setTabMode(TabLayout.MODE_FIXED);
    root.addView(tabs);
    LinearLayout pages = new LinearLayout(this);
    pages.setOrientation(LinearLayout.VERTICAL);
    root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout connectionBody = page(pages);
    LinearLayout audioBody = page(pages);
    LinearLayout tuningBody = page(pages);
    LinearLayout diagnosticsBody = page(pages);
    int[] tabNames = {
      R.string.tab_connection, R.string.tab_audio, R.string.tab_tuning, R.string.tab_diagnostics
    };
    for (int name : tabNames) tabs.addTab(tabs.newTab().setText(name));
    tabs.addOnTabSelectedListener(
        new TabLayout.OnTabSelectedListener() {
          @Override
          public void onTabSelected(TabLayout.Tab tab) {
            for (int i = 0; i < pages.getChildCount(); i++)
              pages.getChildAt(i).setVisibility(i == tab.getPosition() ? View.VISIBLE : View.GONE);
            if (captureTiming != null && tab.getPosition() == 3) updatePipeline();
          }

          @Override
          public void onTabUnselected(TabLayout.Tab tab) {}

          @Override
          public void onTabReselected(TabLayout.Tab tab) {}
        });
    pages.getChildAt(1).setVisibility(View.GONE);
    pages.getChildAt(2).setVisibility(View.GONE);
    pages.getChildAt(3).setVisibility(View.GONE);
    tabs.selectTab(
        tabs.getTabAt(state == null ? 0 : Math.max(0, Math.min(3, state.getInt("tab", 0)))));
    LinearLayout body = connectionBody;
    body.addView(label(getString(R.string.explanation), 16));
    status = label(getString(R.string.idle), 18);
    body.addView(card(status));
    timingStatus = label("", 14);
    body.addView(timingStatus);
    systemStatus = label("", 14);
    body.addView(systemStatus);
    body = audioBody;
    LinearLayout modeControls = new LinearLayout(this);
    modeControls.setOrientation(LinearLayout.VERTICAL);
    modeSelection = label("", 18);
    modeControls.addView(modeSelection);
    MaterialButtonToggleGroup modes = new MaterialButtonToggleGroup(this);
    modes.setSingleSelection(true);
    modes.setSelectionRequired(true);
    int selectedModeId = View.generateViewId();
    int wholeModeId = View.generateViewId();
    for (int modeId : new int[] {selectedModeId, wholeModeId}) {
      MaterialButton mode = new MaterialButton(this);
      mode.setId(modeId);
      mode.setText(modeId == selectedModeId ? R.string.mode_selected : R.string.mode_whole);
      mode.setBackgroundTintList(getColorStateList(R.color.mode_background));
      mode.setTextColor(getColorStateList(R.color.mode_text));
      mode.setStrokeColor(getColorStateList(R.color.mode_stroke));
      mode.setIconTint(getColorStateList(R.color.mode_text));
      mode.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
      mode.setIconPadding(dp(6));
      mode.setMinHeight(dp(48));
      mode.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
      modes.addView(mode);
    }
    modes.check(StreamSettings.load(this).wholeDevice() ? wholeModeId : selectedModeId);
    updateModeSelection(modes);
    modes.addOnButtonCheckedListener(
        (group, id, checked) -> {
          if (!checked) return;
          StreamSettings previous = StreamSettings.load(this);
          previous.withWholeDevice(id == wholeModeId).save(this);
          updateModeSelection(group);
          if (BridgeState.capture != null) BridgeState.capture.reconfigure();
        });
    modeControls.addView(modes);
    modeControls.addView(label(getString(R.string.mode_description), 14));
    body.addView(card(modeControls));
    body = connectionBody;
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
    volume.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
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
    exactVolume = button(getString(R.string.volume_exact), view -> exactVolume());
    volumeControls.addView(exactVolume);
    body.addView(card(volumeControls));
    body.addView(
        button(
            getString(R.string.open_music),
            view -> {
              Intent music = getPackageManager().getLaunchIntentForPackage(BridgeState.MUSIC);
              if (music == null) toast(R.string.music_missing);
              else startActivity(music);
            }));
    body = audioBody;
    LinearLayout latencyControls = new LinearLayout(this);
    latencyControls.setOrientation(LinearLayout.VERTICAL);
    profileSelection = label("", 18);
    latencyControls.addView(profileSelection);
    lowLatencySwitch = new MaterialSwitch(this);
    lowLatencySwitch.setText(R.string.low_latency_mode);
    lowLatencySwitch.setChecked(StreamSettings.load(this).lowLatency());
    lowLatencySwitch.setOnCheckedChangeListener(
        (view, enabled) -> {
          if (updatingSettings) return;
          selectProfile(enabled ? 1 : 0);
        });
    latencyControls.addView(lowLatencySwitch);
    latencyControls.addView(label(getString(R.string.low_latency_description), 14));
    dedicatedCaptureSwitch = new MaterialSwitch(this);
    dedicatedCaptureSwitch.setText(R.string.dedicated_capture);
    dedicatedCaptureSwitch.setOnCheckedChangeListener(
        (view, enabled) -> {
          if (updatingSettings) return;
          StreamSettings.load(this).withDedicatedCapture(enabled).save(this);
          syncSettings();
          if (BridgeState.capture != null) BridgeState.capture.reconfigure();
        });
    latencyControls.addView(dedicatedCaptureSwitch);
    latencyControls.addView(label(getString(R.string.dedicated_capture_description), 14));
    captureBackendStatus = label("", 14);
    latencyControls.addView(captureBackendStatus);
    highQualitySwitch = new MaterialSwitch(this);
    highQualitySwitch.setText(R.string.high_quality_mode);
    highQualitySwitch.setOnCheckedChangeListener(
        (view, enabled) -> {
          if (!updatingSettings) selectProfile(enabled ? 2 : 0);
        });
    latencyControls.addView(highQualitySwitch);
    latencyControls.addView(label(getString(R.string.high_quality_description), 14));
    profileDetails = label("", 14);
    latencyControls.addView(profileDetails);
    adaptiveBufferSwitch = new MaterialSwitch(this);
    adaptiveBufferSwitch.setText(R.string.adaptive_buffer);
    adaptiveBufferSwitch.setOnCheckedChangeListener(
        (view, enabled) -> {
          if (updatingSettings) return;
          StreamSettings.load(this).withAdaptiveBuffer(enabled).save(this);
          syncSettings();
          if (BridgeState.capture != null) BridgeState.capture.reconfigure();
        });
    latencyControls.addView(adaptiveBufferSwitch);
    latencyControls.addView(label(getString(R.string.adaptive_buffer_description), 14));
    body.addView(card(latencyControls));
    LinearLayout compressionControls = new LinearLayout(this);
    compressionControls.setOrientation(LinearLayout.VERTICAL);
    compressionSwitch = new MaterialSwitch(this);
    compressionSwitch.setText(R.string.compressed_audio);
    compressionSwitch.setOnCheckedChangeListener(
        (view, enabled) -> {
          if (updatingSettings) return;
          StreamSettings.load(this).withCompression(enabled).save(this);
          if (BridgeState.capture != null) BridgeState.capture.reconfigure();
        });
    compressionControls.addView(compressionSwitch);
    compressionControls.addView(label(getString(R.string.compressed_audio_description), 14));
    body.addView(card(compressionControls));
    body = tuningBody;
    body.addView(label(getString(R.string.tuning_description), 16));
    LinearLayout deviceControls = new LinearLayout(this);
    deviceControls.setOrientation(LinearLayout.VERTICAL);
    deviceBufferSwitch = new MaterialSwitch(this);
    deviceBufferSwitch.setText(R.string.device_buffer);
    deviceBufferSwitch.setOnCheckedChangeListener(
        (view, enabled) -> {
          if (updatingSettings) return;
          StreamSettings.load(this).withDeviceBuffer(enabled).save(this);
          syncSettings();
          if (BridgeState.capture != null) BridgeState.capture.reconfigure();
        });
    deviceControls.addView(deviceBufferSwitch);
    deviceControls.addView(label(getString(R.string.device_buffer_description), 14));
    minimumBufferSwitch = new MaterialSwitch(this);
    minimumBufferSwitch.setText(R.string.device_buffer_faster);
    minimumBufferSwitch.setOnCheckedChangeListener(
        (view, enabled) -> {
          if (updatingSettings) return;
          StreamSettings.load(this).withDeviceBufferFrames(enabled ? 928 : 1856).save(this);
          syncSettings();
          if (BridgeState.capture != null) BridgeState.capture.reconfigure();
        });
    deviceControls.addView(minimumBufferSwitch);
    deviceControls.addView(label(getString(R.string.device_buffer_faster_description), 14));
    body.addView(card(deviceControls));
    body.addView(button(getString(R.string.buffer_settings), view -> bufferSettings()));
    body.addView(label(getString(R.string.notes), 14));
    body.addView(button(getString(R.string.licenses), view -> licenses()));
    body = diagnosticsBody;
    body.addView(label(getString(R.string.diagnostics_intro), 14));
    captureTiming = label("", 16);
    body.addView(card(captureTiming));
    phoneBufferTiming = label("", 16);
    body.addView(card(phoneBufferTiming));
    pipeTiming = label("", 16);
    body.addView(card(pipeTiming));
    senderTiming = label("", 16);
    body.addView(card(senderTiming));
    handoffTiming = label("", 16);
    body.addView(card(handoffTiming));
    networkTiming = label("", 16);
    body.addView(card(networkTiming));
    body.addView(card(label(getString(R.string.diagnostics_unmeasured), 14)));
    syncSettings();
    setContentView(root);
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putInt("tab", tabs.getSelectedTabPosition());
    super.onSaveInstanceState(state);
  }

  private LinearLayout page(LinearLayout parent) {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(24), dp(8), dp(24), dp(24));
    scroll.addView(content);
    parent.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    return content;
  }

  private void selectProfile(int profile) {
    StreamSettings.load(this).withProfile(profile).save(this);
    syncSettings();
    if (BridgeState.capture != null) BridgeState.capture.reconfigure();
  }

  private void syncSettings() {
    StreamSettings settings = StreamSettings.load(this);
    updatingSettings = true;
    lowLatencySwitch.setChecked(settings.lowLatency());
    dedicatedCaptureSwitch.setChecked(settings.dedicatedCapture());
    dedicatedCaptureSwitch.setEnabled(settings.lowLatency() && RootConnector.available());
    highQualitySwitch.setChecked(settings.highQuality());
    compressionSwitch.setChecked(settings.compressedAudio());
    adaptiveBufferSwitch.setChecked(settings.adaptiveBuffer());
    adaptiveBufferSwitch.setEnabled(settings.lowLatency());
    deviceBufferSwitch.setChecked(settings.deviceBuffer());
    deviceBufferSwitch.setEnabled(settings.lowLatency() && !settings.effectiveDedicatedCapture());
    minimumBufferSwitch.setChecked(settings.deviceBufferFrames() == 928);
    minimumBufferSwitch.setEnabled(
        settings.effectiveDeviceBuffer() && !settings.effectiveDedicatedCapture());
    profileSelection.setText(
        getString(
            R.string.profile_selected,
            getString(
                settings.lowLatency()
                    ? R.string.low_latency_mode
                    : settings.highQuality()
                        ? R.string.high_quality_mode
                        : R.string.profile_custom)));
    if (settings.effectiveAdaptiveBuffer()) {
      profileDetails.setText(
          getString(
              R.string.profile_values_adaptive,
              settings.effectiveDedicatedCapture() ? 1 : settings.effectiveBlockMs(),
              settings.effectiveLatencyMs()));
    } else
      profileDetails.setText(
          getString(
              R.string.profile_values,
              settings.effectiveBufferMs(),
              settings.effectiveDedicatedCapture() ? 1 : settings.effectiveBlockMs(),
              settings.effectiveLatencyMs()));
    updatingSettings = false;
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
      if (!StreamSettings.load(this).wholeDevice())
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
    timingChanged();
    if (status == null) return;
    syncSettings();
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
    volume.setContentDescription(getString(R.string.volume, BridgeState.volume));
    volume.setValue(VolumePolicy.apply(BridgeState.volume));
    volume.setEnabled(BridgeState.connected);
    exactVolume.setEnabled(BridgeState.connected);
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
                if (HomePodRoutes.available()) {
                  HomePodRoutes.selectFromSystem(BridgeState.MUSIC, receiver.id());
                } else {
                  BridgeState.capture.select(
                      receiver,
                      success -> {
                        if (!success) toast(R.string.connection_failed);
                      });
                }
              });
      speaker.setSelected(receiver.equals(BridgeState.selected));
      speakers.addView(speaker);
    }
    if (!BridgeState.receivers.isEmpty() && BridgeState.status == R.string.searching)
      status.setText(R.string.ready);
  }

  @Override
  public void timingChanged() {
    if (captureTiming == null) return;
    if (tabs.getSelectedTabPosition() == 3) updatePipeline();
    updateTargetTiming();
  }

  private void updateTargetTiming() {
    int[] timing = BridgeState.timing;
    boolean showTiming = BridgeState.connected && timing != null;
    timingStatus.setVisibility(showTiming ? View.VISIBLE : View.GONE);
    if (showTiming) {
      String minimum =
          timing[2] < 0
              ? getString(R.string.latency_unknown)
              : getString(R.string.latency_ms, timing[2]);
      timingStatus.setText(
          getString(R.string.latency_active, timing[1], timing[0], minimum)
              + (timing[3] != 0 ? "\n" + getString(R.string.latency_fallback) : ""));
    }
  }

  private String millis(long micros) {
    return micros < 0
        ? getString(R.string.diagnostics_unknown)
        : getString(R.string.diagnostics_ms, micros / 1000.0);
  }

  private String framesTime(long frames) {
    return frames < 0
        ? getString(R.string.diagnostics_unknown)
        : millis(frames * 1_000_000L / 44100);
  }

  private void updatePipeline() {
    if (captureBackendStatus != null)
      captureBackendStatus.setText(
          BridgeState.selected == null
              ? R.string.diagnostics_idle
              : switch (BridgeState.captureBackend) {
                case STANDARD -> R.string.capture_backend_standard;
                case CHECKING -> R.string.capture_backend_checking;
                case DEDICATED -> R.string.capture_backend_active;
                case DEDICATED_TEST -> R.string.capture_backend_test;
                case FALLBACK -> R.string.capture_backend_fallback;
              });
    boolean live = BridgeState.captureBackend == BridgeState.CaptureBackend.DEDICATED;
    PipelineTiming t = BridgeState.connected ? BridgeState.pipeline : null;
    if (t == null) {
      for (TextView label :
          new TextView[] {
            captureTiming, phoneBufferTiming, pipeTiming, senderTiming, handoffTiming, networkTiming
          }) label.setText(R.string.diagnostics_idle);
      return;
    }
    String request =
        getString(
            t.capture(9) == 1
                ? R.string.capture_fast_requested
                : t.capture(9) == 2
                    ? R.string.capture_fast_fallback
                    : t.capture(9) == 0 ? R.string.capture_standard : R.string.diagnostics_unknown);
    OwnedAudioPath.Update path = BridgeState.phonePath;
    phoneBufferTiming.setText(
        live
            ? getString(R.string.device_buffer_fast_capture)
            : t.halQueuedFrames() >= 0
                ? getString(R.string.device_buffer_hal_test)
                : path == null
                    ? getString(R.string.device_buffer_off)
                    : switch (path.state()) {
                      case CHECKING -> getString(R.string.device_buffer_checking);
                      case ACTIVE ->
                          getString(R.string.device_buffer_active, framesTime(path.targetFrames()));
                      case STANDARD -> getString(R.string.device_buffer_standard);
                      case RECOVERY_REQUIRED -> getString(R.string.device_buffer_recovering);
                    });
    captureTiming.setText(
        live
            ? (t.halQueuedFrames() < 0
                ? getString(R.string.diagnostics_hal_idle)
                : getString(R.string.diagnostics_hal_capture_live, framesTime(t.halQueuedFrames())))
            : t.halQueuedFrames() >= 0
                ? getString(R.string.diagnostics_hal_capture, framesTime(t.halQueuedFrames()))
                : getString(
                    R.string.diagnostics_capture,
                    millis(t.captureAgeFresh() ? t.capture(1) : -1),
                    millis(t.capture(3)),
                    millis(t.capture(4)),
                    framesTime(t.capture(8)),
                    framesTime(t.capture(7)),
                    request));
    pipeTiming.setText(
        getString(
            R.string.diagnostics_pipe,
            millis(t.pipeMicros()),
            millis(t.capture(5)),
            millis(t.capture(6))));
    senderTiming.setText(
        getString(
            R.string.diagnostics_sender,
            framesTime(t.sender(0)),
            framesTime(t.sender(1)),
            millis(t.sender(2)),
            framesTime(t.sender(10)),
            framesTime(t.sender(7))));
    // Negative lead is meaningful lateness, unlike an unavailable positive duration.
    String last =
        t.sender(6) > 0
            ? getString(R.string.diagnostics_ms, t.sender(4) / 1000.0)
            : getString(R.string.diagnostics_unknown);
    String minimum =
        t.sender(6) > 0
            ? getString(R.string.diagnostics_ms, t.sender(3) / 1000.0)
            : getString(R.string.diagnostics_unknown);
    int[] targets = BridgeState.timing;
    handoffTiming.setText(
        getString(
            R.string.diagnostics_handoff,
            last,
            minimum,
            Math.max(0, t.sender(5)),
            Math.max(0, t.sender(6)),
            targets == null
                ? getString(R.string.diagnostics_unknown)
                : millis(targets[1] * 1000L)));
    networkTiming.setText(
        getString(R.string.diagnostics_network, millis(t.sender(11)), millis(t.sender(12))));
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

  private void exactVolume() {
    if (!BridgeState.connected) return;
    TextInputLayout field = new TextInputLayout(this);
    field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
    field.setHint(getString(R.string.volume_range));
    field.setPadding(dp(20), dp(8), dp(20), 0);
    TextInputEditText input = new TextInputEditText(field.getContext());
    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(3)});
    input.setText(Integer.toString(BridgeState.volume));
    input.selectAll();
    field.addView(input, new LinearLayout.LayoutParams(-1, -2));
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.volume_exact)
        .setView(field)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(
            R.string.apply_settings,
            (dialog, which) -> {
              try {
                int requested = Integer.parseInt(input.getText().toString());
                if (requested < 0 || requested > 100) throw new NumberFormatException();
                if (!BridgeState.connected) return;
                BridgeState.volume = requested;
                NativeSender.volume(requested);
                BridgeState.publish();
              } catch (NumberFormatException e) {
                toast(R.string.volume_invalid);
              }
            })
        .show();
  }

  private void bufferSettings() {
    StreamSettings settings = StreamSettings.load(this);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), 0, dp(20), 0);
    Slider buffering =
        settingSlider(content, R.string.buffer_amount, 0, 300, 1, settings.effectiveBufferMs());
    Slider block =
        settingSlider(content, R.string.capture_block, 2, 40, 1, settings.effectiveBlockMs());
    Slider latency =
        settingSlider(
            content, R.string.receiver_latency, 10, 2000, 10, settings.effectiveLatencyMs());
    content.addView(label(getString(R.string.buffer_description), 14));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(content);
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.buffer_settings)
        .setView(scroll)
        .setNeutralButton(
            R.string.restore_defaults, (dialog, which) -> saveBufferSettings(150, 10, 1500))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(
            R.string.apply_settings,
            (dialog, which) ->
                saveBufferSettings(
                    Math.round(buffering.getValue()),
                    Math.round(block.getValue()),
                    Math.round(latency.getValue())))
        .show();
  }

  private void updateModeSelection(MaterialButtonToggleGroup group) {
    for (int i = 0; i < group.getChildCount(); i++) {
      MaterialButton mode = (MaterialButton) group.getChildAt(i);
      mode.setIconResource(mode.isChecked() ? R.drawable.ic_check : 0);
      mode.setStrokeWidth(dp(mode.isChecked() ? 2 : 1));
      mode.setTypeface(null, mode.isChecked() ? Typeface.BOLD : Typeface.NORMAL);
      mode.setStateDescription(
          getString(mode.isChecked() ? R.string.mode_active : R.string.mode_inactive));
    }
    modeSelection.setText(
        getString(
            R.string.capture_mode,
            getString(
                StreamSettings.load(this).wholeDevice()
                    ? R.string.mode_whole
                    : R.string.mode_selected)));
  }

  private Slider settingSlider(
      LinearLayout content, int title, int min, int max, int step, int value) {
    TextView heading = label(getString(title, value), 16);
    content.addView(heading);
    Slider slider = new Slider(this);
    slider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    slider.setValueFrom(min);
    slider.setValueTo(max);
    slider.setStepSize(step);
    slider.setValue(value);
    slider.setContentDescription(getString(title, value));
    slider.setLabelFormatter(number -> Math.round(number) + " ms");
    slider.addOnChangeListener(
        (view, number, fromUser) -> {
          heading.setText(getString(title, Math.round(number)));
          view.setContentDescription(getString(title, Math.round(number)));
        });
    content.addView(slider);
    return slider;
  }

  private void saveBufferSettings(int bufferMs, int blockMs, int latencyMs) {
    StreamSettings.load(this).withBuffers(bufferMs, blockMs, latencyMs).save(this);
    syncSettings();
    if (BridgeState.capture != null) BridgeState.capture.reconfigure();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void toast(int string) {
    Toast.makeText(this, string, Toast.LENGTH_LONG).show();
  }
}
