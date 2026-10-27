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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
  private TextView connectionTiming;
  private TextView phoneBufferTiming;
  private TextView diagnosticsEmpty;
  private View diagnosticsEmptyCard;
  private View connectionTimingCard;
  private LinearLayout diagnosticsDetails;
  private LinearLayout speakers;
  private TextView speakerHint;
  private Button share;
  private int textColor;
  private int cardColor;
  private Slider volume;
  private Button exactVolume;
  private TextView volumeLabel;
  private TextView volumeHint;
  private TextView systemStatus;
  private TextView modeSelection;
  private TextView captureBackendStatus;
  private TextView profileSelection;
  private TextView profileDetails;
  private TextView receiverTimingSelection;
  private TextView manualSummary;
  private TabLayout tabs;
  private Button profileButton;
  private final android.os.Handler sharingHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());
  private long sharingAttempt;
  private boolean awaitingSystemBridge;
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
    if (BridgeState.capture == null) BridgeState.receiverRejection = ReceiverRejection.load(this);
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
            new ColorStateList(
                new int[][] {{-android.R.attr.state_enabled}, {}},
                new int[] {
                  ColorUtils.setAlphaComponent(textColor, 31),
                  MaterialColors.getColor(share, androidx.appcompat.R.attr.colorPrimary)
                }));
    share.setTextColor(
        new ColorStateList(
            new int[][] {{-android.R.attr.state_enabled}, {}},
            new int[] {
              ColorUtils.setAlphaComponent(textColor, 97),
              MaterialColors.getColor(share, com.google.android.material.R.attr.colorOnPrimary)
            }));
    ((MaterialButton) share).setStrokeWidth(0);
    LinearLayout speakerHeader = new LinearLayout(this);
    speakerHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
    TextView speakerTitle = label(getString(R.string.speaker_section), 18);
    speakerTitle.setTypeface(null, Typeface.BOLD);
    androidx.core.view.ViewCompat.setAccessibilityHeading(speakerTitle, true);
    speakerHeader.addView(speakerTitle, new LinearLayout.LayoutParams(0, -2, 1));
    MaterialButton search = new MaterialButton(this);
    search.setText(R.string.speaker_search);
    search.setContentDescription(getString(R.string.refresh));
    search.setAllCaps(false);
    search.setTextColor(
        controlColors(MaterialColors.getColor(search, androidx.appcompat.R.attr.colorPrimary), 97));
    search.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
    search.setStrokeWidth(0);
    search.setMinHeight(dp(48));
    search.setIconResource(R.drawable.ic_search);
    search.setIconSize(dp(18));
    search.setIconTint(search.getTextColors());
    search.setOnClickListener(view -> refresh());
    speakerHeader.addView(search, new LinearLayout.LayoutParams(-2, -2));
    body.addView(speakerHeader);
    speakerHint = label("", 14);
    body.addView(speakerHint);
    speakers = new LinearLayout(this);
    speakers.setOrientation(LinearLayout.VERTICAL);
    body.addView(speakers);
    LinearLayout volumeControls = new LinearLayout(this);
    volumeControls.setOrientation(LinearLayout.VERTICAL);
    volumeLabel = label(getString(R.string.volume, 0), 18);
    volumeControls.addView(volumeLabel);
    volumeHint = label(getString(R.string.volume_unavailable_hint), 14);
    volumeControls.addView(volumeHint);
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
    profileButton = button(getString(R.string.profile_choose), view -> chooseProfile());
    latencyControls.addView(profileButton);
    profileDetails = label("", 14);
    latencyControls.addView(profileDetails);
    captureBackendStatus = label("", 14);
    latencyControls.addView(captureBackendStatus);
    latencyControls.addView(label(getString(R.string.profiles_description), 14));
    body.addView(card(latencyControls));
    body = tuningBody;
    body.addView(label(getString(R.string.manual_description), 16));
    body.addView(button(getString(R.string.manual_settings), view -> manualSettings()));
    body.addView(button(getString(R.string.network_settings), view -> networkSettings()));
    manualSummary = label("", 14);
    body.addView(manualSummary);
    receiverTimingSelection = label("", 14);
    body.addView(receiverTimingSelection);
    body.addView(label(getString(R.string.notes), 14));
    body.addView(button(getString(R.string.licenses), view -> licenses()));
    body = diagnosticsBody;
    diagnosticsEmpty = label(getString(R.string.diagnostics_idle), 16);
    diagnosticsEmptyCard = card(diagnosticsEmpty);
    body.addView(diagnosticsEmptyCard);
    connectionTiming = label("", 16);
    connectionTimingCard = card(connectionTiming);
    body.addView(connectionTimingCard);
    diagnosticsDetails = new LinearLayout(this);
    diagnosticsDetails.setOrientation(LinearLayout.VERTICAL);
    body.addView(diagnosticsDetails);
    body = diagnosticsDetails;
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
    updateVolumeControls();
    updatePipeline();
    updateSpeakers();
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

  private void chooseProfile() {
    var settings = StreamSettings.load(this);
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.profile_choose)
        .setSingleChoiceItems(
            new String[] {
              getString(R.string.profile_balanced),
              getString(R.string.low_latency_mode),
              getString(R.string.high_quality_mode),
              getString(R.string.profile_manual)
            },
            settings.profile(),
            (dialog, which) -> {
              applySettings(
                  which == 3
                      ? StreamSettings.savedManual(this)
                      : StreamSettings.load(this).preset(which));
              dialog.dismiss();
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void manualSettings() {
    ManualSettingsDialog.show(this, StreamSettings.savedManual(this), this::applySettings);
  }

  private void applySettings(StreamSettings settings) {
    settings.save(this);
    syncSettings();
    if (BridgeState.capture != null) BridgeState.capture.reconfigure();
  }

  private void syncSettings() {
    StreamSettings settings = StreamSettings.load(this);
    profileSelection.setText(
        getString(
            R.string.profile_selected,
            getString(
                settings.lowLatency()
                    ? R.string.low_latency_mode
                    : settings.highQuality()
                        ? R.string.high_quality_mode
                        : settings.manualMode()
                            ? R.string.profile_manual
                            : R.string.profile_balanced)));
    if (settings.effectiveAdaptiveBuffer()) {
      profileDetails.setText(
          getString(
              R.string.profile_values_adaptive,
              settings.captureUnitMs(),
              settings.requestedPresentationMs()));
    } else
      profileDetails.setText(
          getString(
              R.string.profile_values,
              settings.effectiveBufferMs(),
              settings.captureUnitMs(),
              settings.requestedPresentationMs()));
    var manual = StreamSettings.savedManual(this);
    manualSummary.setText(
        getString(
            R.string.manual_saved_summary,
            manual.phoneTiming().actualPeriodMs(),
            manual.phoneTiming().reserveMs(),
            manual.effectiveBufferMs(),
            manual.requestedPacketFrames()));
    receiverTimingSelection.setText(
        getString(
            settings.receiverTiming().independent()
                ? R.string.receiver_timing_selected
                : R.string.receiver_timing_linked,
            settings.requestedReceiverMinimumMs(),
            settings.requestedReceiverMaximumMs(),
            settings.requestedPresentationMs()));
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
    sharingAttempt++;
    awaitingSystemBridge = false;
    sharingHandler.removeCallbacksAndMessages(null);
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
    HomePodRoutes.ensureDiscovery();
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
    if (awaitingSystemBridge) return;
    long attempt = ++sharingAttempt;
    awaitingSystemBridge = true;
    RootConnector.request(this);
    changed();
    awaitSystemSharing(attempt, android.os.SystemClock.uptimeMillis() + 3000);
  }

  private void awaitSystemSharing(long attempt, long deadline) {
    if (attempt != sharingAttempt || isFinishing() || isDestroyed()) return;
    if (RootConnector.available()) {
      awaitingSystemBridge = false;
      changed();
      startForegroundService(new Intent(this, CaptureService.class).putExtra("system", true));
      return;
    }
    if (android.os.SystemClock.uptimeMillis() < deadline) {
      sharingHandler.postDelayed(() -> awaitSystemSharing(attempt, deadline), 50);
      return;
    }
    awaitingSystemBridge = false;
    changed();
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.system_unavailable_title)
        .setMessage(R.string.system_unavailable_description)
        .setPositiveButton(R.string.system_retry, (dialog, which) -> requestSharing())
        .setNeutralButton(R.string.standard_sharing, (dialog, which) -> requestStandardSharing())
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void requestStandardSharing() {
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
    if (code == 2) requestStandardSharing();
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
    updateConnectionStatus();
    share.setText(
        awaitingSystemBridge
            ? R.string.preparing_sharing
            : BridgeState.capture == null ? R.string.enable : R.string.stop);
    share.setEnabled(!awaitingSystemBridge);
    systemStatus.setText(
        awaitingSystemBridge
            ? R.string.system_connecting
            : RootConnector.available() ? R.string.system_ready : R.string.system_missing);
    updateVolumeControls();
    updateSpeakers();
  }

  private void updateSpeakers() {
    boolean ready = BridgeState.capture != null && !awaitingSystemBridge;
    speakerHint.setText(ready ? R.string.speaker_choose_hint : R.string.speaker_enable_hint);
    speakerHint.setVisibility(
        BridgeState.receivers.isEmpty() || BridgeState.selected != null ? View.GONE : View.VISIBLE);
    speakers.removeAllViews();
    if (BridgeState.receivers.isEmpty()) {
      speakers.addView(card(label(getString(R.string.speaker_empty), 16)));
      return;
    }
    for (Receiver receiver : BridgeState.receivers) {
      boolean selected =
          receiver.id().equals(BridgeState.selected == null ? null : BridgeState.selected.id());
      boolean connected = selected && BridgeState.connected;
      LinearLayout row = new LinearLayout(this);
      row.setGravity(android.view.Gravity.CENTER_VERTICAL);
      ImageView icon = new ImageView(this);
      icon.setImageResource(R.drawable.ic_speaker);
      icon.setImageTintList(ColorStateList.valueOf(textColor));
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LinearLayout.LayoutParams iconLayout = new LinearLayout.LayoutParams(dp(32), dp(32));
      iconLayout.setMarginEnd(dp(16));
      row.addView(icon, iconLayout);
      LinearLayout details = new LinearLayout(this);
      details.setOrientation(LinearLayout.VERTICAL);
      TextView name = label(receiver.name(), 18);
      name.setTypeface(null, Typeface.BOLD);
      name.setPadding(0, dp(8), 0, dp(2));
      details.addView(name);
      TextView state =
          label(
              getString(
                  connected
                      ? R.string.speaker_connected
                      : selected ? R.string.speaker_connecting : R.string.speaker_available),
              14);
      state.setPadding(0, 0, 0, dp(8));
      if (selected)
        state.setTextColor(MaterialColors.getColor(state, androidx.appcompat.R.attr.colorPrimary));
      details.addView(state);
      LinearLayout.LayoutParams detailsLayout = new LinearLayout.LayoutParams(0, -2, 1);
      detailsLayout.setMarginEnd(dp(12));
      row.addView(details, detailsLayout);
      if (!selected) {
        Button connect =
            button(getString(R.string.speaker_connect), view -> connectSpeaker(receiver));
        connect.setContentDescription(
            getString(R.string.speaker_connect_description, receiver.name()));
        connect.setEnabled(ready);
        connect.setMinWidth(dp(64));
        connect.setMinHeight(dp(48));
        connect.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.addView(connect, new LinearLayout.LayoutParams(-2, -2));
      } else if (connected) {
        ImageView check = new ImageView(this);
        check.setImageResource(R.drawable.ic_check);
        check.setImageTintList(
            ColorStateList.valueOf(
                MaterialColors.getColor(check, androidx.appcompat.R.attr.colorPrimary)));
        check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(check, new LinearLayout.LayoutParams(dp(24), dp(24)));
      }
      MaterialCardView device = card(row);
      if (selected) {
        device.setStrokeWidth(dp(2));
        device.setStrokeColor(
            MaterialColors.getColor(device, androidx.appcompat.R.attr.colorPrimary));
      }
      speakers.addView(device);
    }
  }

  private void connectSpeaker(Receiver receiver) {
    if (BridgeState.capture == null || awaitingSystemBridge) return;
    if (HomePodRoutes.available()) {
      HomePodRoutes.selectFromSystem(BridgeState.MUSIC, receiver.id());
    } else {
      BridgeState.capture.select(
          receiver,
          success -> {
            if (!success) {
              String reason = BridgeState.receiverRejection.message(this, false);
              Toast.makeText(
                      this,
                      reason.isEmpty() ? getString(R.string.connection_failed) : reason,
                      Toast.LENGTH_LONG)
                  .show();
            }
          });
    }
  }

  private void updateConnectionStatus() {
    if (status == null) return;
    String message;
    if (BridgeState.selected != null && BridgeState.status == R.string.connecting)
      message = getString(R.string.connecting, BridgeState.selected.name());
    else if (BridgeState.selected != null && BridgeState.status == R.string.playing)
      message = getString(R.string.playing, BridgeState.selected.name());
    else if (!BridgeState.receivers.isEmpty() && BridgeState.status == R.string.searching)
      message = getString(R.string.ready);
    else message = getString(BridgeState.status);
    String reason = BridgeState.receiverRejection.message(this, BridgeState.connected);
    status.setText(reason.isEmpty() ? message : message + "\n\n" + reason);
  }

  private void updateVolumeControls() {
    boolean connected = BridgeState.connected;
    String title =
        connected
            ? getString(R.string.volume, BridgeState.volume)
            : getString(R.string.volume_unavailable);
    volumeLabel.setText(title);
    volumeLabel.setTextColor(connected ? textColor : ColorUtils.setAlphaComponent(textColor, 97));
    volumeHint.setVisibility(connected ? View.GONE : View.VISIBLE);
    volume.setContentDescription(
        connected ? title : title + ". " + getString(R.string.volume_unavailable_hint));
    volume.setValue(connected ? VolumePolicy.apply(BridgeState.volume) : 0);
    volume.setEnabled(connected);
    exactVolume.setEnabled(connected);
  }

  @Override
  public void timingChanged() {
    updateConnectionStatus();
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
              + (timing.length >= 8
                  ? "\n"
                      + getString(
                          R.string.receiver_timing_active,
                          timing[4],
                          timing[5],
                          timing[6] < 0
                              ? getString(R.string.latency_unknown)
                              : getString(R.string.latency_ms, timing[6]))
                  : "")
              + (timing.length >= 8 && timing[7] != 0
                  ? "\n" + getString(R.string.receiver_timing_explicit)
                  : "")
              + (timing[3] != 0 ? "\n" + getString(R.string.latency_fallback) : ""));
    }
  }

  private void networkSettings() {
    int[] selected = {NetworkSettings.load(this).priority() ? 1 : 0};
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.network_settings)
        .setSingleChoiceItems(
            new String[] {getString(R.string.network_normal), getString(R.string.network_priority)},
            selected[0],
            (dialog, which) -> selected[0] = which)
        .setPositiveButton(
            R.string.apply_settings,
            (dialog, which) -> {
              new NetworkSettings(selected[0] == 1).save(this);
              if (BridgeState.capture != null) BridgeState.capture.reconfigure();
            })
        .setNeutralButton(
            R.string.network_details,
            (dialog, which) ->
                new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.network_priority_description)
                    .setPositiveButton(android.R.string.ok, null)
                    .show())
        .setNegativeButton(android.R.string.cancel, null)
        .show();
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
    ConnectionTiming connection = BridgeState.connectionTiming;
    PipelineTiming t = BridgeState.connected ? BridgeState.pipeline : null;
    boolean connecting = BridgeState.selected != null && !BridgeState.connected;
    diagnosticsEmptyCard.setVisibility(t == null ? View.VISIBLE : View.GONE);
    diagnosticsEmpty.setText(
        BridgeState.connected
            ? R.string.diagnostics_waiting
            : connecting ? R.string.diagnostics_connecting : R.string.diagnostics_idle);
    diagnosticsDetails.setVisibility(t == null ? View.GONE : View.VISIBLE);
    connectionTimingCard.setVisibility(
        connection != null && (BridgeState.connected || connecting) ? View.VISIBLE : View.GONE);
    connectionTiming.setText(
        connection == null
            ? getString(R.string.diagnostics_idle)
            : getString(
                R.string.diagnostics_connection,
                millis(
                    connection.micros(
                        ConnectionTiming.Stage.SELECTED, ConnectionTiming.Stage.READY)),
                millis(
                    connection.micros(
                        ConnectionTiming.Stage.SELECTED, ConnectionTiming.Stage.WORKER)),
                millis(
                    connection.micros(
                        ConnectionTiming.Stage.WORKER, ConnectionTiming.Stage.NETWORK)),
                millis(
                    connection.micros(
                        ConnectionTiming.Stage.NETWORK, ConnectionTiming.Stage.CAPTURE)),
                millis(
                    connection.micros(
                        ConnectionTiming.Stage.SENDER, ConnectionTiming.Stage.AUTHENTICATED))));
    if (captureBackendStatus != null)
      captureBackendStatus.setText(
          BridgeState.selected == null
              ? R.string.diagnostics_idle
              : switch (BridgeState.captureBackend) {
                case STANDARD -> R.string.capture_backend_standard;
                case CHECKING -> R.string.capture_backend_checking;
                case DEDICATED -> R.string.capture_backend_active;
                case DEDICATED_SHORT -> R.string.capture_backend_short;
                case DEDICATED_TEST -> R.string.capture_backend_test;
                case FALLBACK -> R.string.capture_backend_fallback;
              });
    if (BridgeState.captureBackend == BridgeState.CaptureBackend.DEDICATED_SHORT
        && BridgeState.selected != null
        && BridgeState.activePhoneTiming != null)
      captureBackendStatus.setText(
          getString(
              R.string.capture_backend_period, BridgeState.activePhoneTiming.actualPeriodMs()));
    boolean live =
        BridgeState.captureBackend == BridgeState.CaptureBackend.DEDICATED
            || BridgeState.captureBackend == BridgeState.CaptureBackend.DEDICATED_SHORT;
    if (t == null) return;
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
            ? getString(
                R.string.device_buffer_dedicated_target,
                framesTime(
                    BridgeState.captureBackend == BridgeState.CaptureBackend.DEDICATED_SHORT
                        ? BridgeState.activePhoneTiming == null
                            ? 800
                            : BridgeState.activePhoneTiming.reserveFrames()
                        : 800))
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
                : getString(
                    R.string.diagnostics_hal_capture_live,
                    framesTime(t.halQueuedFrames()),
                    framesTime(BufferLimits.DEDICATED_FRAMES)))
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
    NetworkTiming network =
        BridgeState.networkTiming == null ? new NetworkTiming(null, 0) : BridgeState.networkTiming;
    networkTiming.setText(
        getString(R.string.diagnostics_network, millis(t.sender(11)), millis(t.sender(12)))
            + "\n\n"
            + getString(
                R.string.diagnostics_network_tx,
                millis(network.transit(6)),
                millis(network.transit(7)),
                millis(network.transit(8)),
                network.value(3) < 0
                    ? getString(R.string.diagnostics_unknown)
                    : Long.toString(network.value(3)),
                Math.max(0, network.value(1)),
                Math.max(0, network.value(0)),
                Math.max(0, network.value(5)),
                getString(network.priorityResource()),
                getString(network.wifiResource()),
                Math.max(0, network.value(14)),
                Math.max(0, network.value(15)),
                Math.max(0, network.value(16))));
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
    button.setTextColor(controlColors(textColor, 97));
    button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
    button.setStrokeWidth(dp(1));
    button.setStrokeColor(
        controlColors(
            MaterialColors.getColor(button, com.google.android.material.R.attr.colorOutline), 31));
    button.setPadding(dp(20), dp(12), dp(20), dp(12));
    LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
    layout.setMargins(0, dp(6), 0, dp(6));
    button.setLayoutParams(layout);
    button.setOnClickListener(listener);
    return button;
  }

  private ColorStateList controlColors(int color, int disabledAlpha) {
    return new ColorStateList(
        new int[][] {{android.R.attr.state_enabled}, {}},
        new int[] {color, ColorUtils.setAlphaComponent(color, disabledAlpha)});
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

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void toast(int string) {
    Toast.makeText(this, string, Toast.LENGTH_LONG).show();
  }
}
