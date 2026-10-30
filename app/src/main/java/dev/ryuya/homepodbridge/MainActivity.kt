package dev.ryuya.homepodbridge

import android.Manifest
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaRouter2
import android.media.MediaRouter2.RouteCallback
import android.media.RouteDiscoveryPreference.Builder
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputFilter.LengthFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.View.OnApplyWindowInsetsListener
import android.view.WindowInsets
import android.view.WindowInsets.Type
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.button.MaterialButtonToggleGroup.OnButtonCheckedListener
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.slider.Slider.OnChangeListener
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.google.android.material.tabs.TabLayout.Tab
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.FileDescriptor
import java.io.IOException
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.util.List
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

class MainActivity : AppCompatActivity(), BridgeState.Observer {
    private var status: TextView? = null
    private var timingStatus: TextView? = null
    private var captureTiming: TextView? = null
    private var pipeTiming: TextView? = null
    private var senderTiming: TextView? = null
    private var handoffTiming: TextView? = null
    private var networkTiming: TextView? = null
    private var connectionTiming: TextView? = null
    private var phoneBufferTiming: TextView? = null
    private var diagnosticsEmpty: TextView? = null
    private var diagnosticsEmptyCard: View? = null
    private var connectionTimingCard: View? = null
    private var diagnosticsDetails: LinearLayout? = null
    private var speakers: LinearLayout? = null
    private var speakerHint: TextView? = null
    private var share: Button? = null
    private var textColor: Int = 0
    private var cardColor: Int = 0
    private var volume: Slider? = null
    private var exactVolume: Button? = null
    private var volumeLabel: TextView? = null
    private var volumeHint: TextView? = null
    private var systemStatus: TextView? = null
    private var modeSelection: TextView? = null
    private var captureBackendStatus: TextView? = null
    private var profileSelection: TextView? = null
    private var profileDetails: TextView? = null
    private var receiverTimingSelection: TextView? = null
    private var manualSummary: TextView? = null
    private var tabs: TabLayout? = null
    private var profileButton: Button? = null
    private val sharingHandler: Handler = Handler(Looper.getMainLooper())
    private var sharingAttempt: Long = 0
    private var awaitingSystemBridge: Boolean = false
    private var router: MediaRouter2? = null
    private val routes: RouteCallback = object : RouteCallback() {}

    override fun dump(
        prefix: String,
        fd: FileDescriptor?,
        writer: PrintWriter,
        args: Array<out String>?,
    ) {
        if (args != null && args.size == 1 && args[0] == "--measure-capture-tuned") {
            CaptureProbe.request(this, 2)
            CaptureProbe.dump(writer!!)
            return
        }
        if (args != null && args.size == 1 && args[0] == "--measure-capture-guard") {
            CaptureProbe.request(this, true)
            CaptureProbe.dump(writer!!)
            return
        }
        if (args != null && args.size == 1 && args[0] == "--measure-capture") {
            CaptureProbe.request(this)
            CaptureProbe.dump(writer!!)
            return
        }
        if (args != null && args.size == 1 && args[0] == "--capture-probe-status") {
            CaptureProbe.dump(writer!!)
            HalInspection.dump(writer!!, false)
            return
        }
        if (args != null && args.size == 1 && args[0] == "--inspect-dedicated-route") {
            HalInspection.requestRoute(this, BridgeState.captureRegistration)
            HalInspection.dump(writer!!, false)
            return
        }
        if (args != null && args.size > 0 && args[0] == "--inspect-dedicated-hal") {
            if (args.size == 2 && args[1]!!.matches("[0-2]".toRegex()))
                HalInspection.request(args[1]!!.toInt())
            HalInspection.dump(writer!!, false)
            return
        }
        if (args != null && args.size == 1 && args[0] == "--dedicated-hal-image") {
            HalInspection.dump(writer!!, true)
            return
        }
        super.dump(prefix!!, fd, writer!!, args)
    }

    public override fun onCreate(state: Bundle?) {
        setTheme(R.style.Theme_HomePodBridge)
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(state)
        if (BridgeState.capture == null)
            BridgeState.receiverRejection = ReceiverRejection.load(this)
        textColor =
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurface,
                Color.BLACK,
            )
        cardColor =
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSurfaceContainer,
                Color.WHITE,
            )
        val root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setBackgroundColor(
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSurface,
                Color.BLACK,
            )
        )
        root.setOnApplyWindowInsetsListener(
            OnApplyWindowInsetsListener { view: View?, insets: WindowInsets? ->
                val bars = insets!!.getInsets(Type.systemBars())
                root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets!!
            }
        )
        val title = label(getString(R.string.intro), 30)
        title.setTypeface(null, Typeface.BOLD)
        title.setPadding(dp(24), dp(16), dp(24), dp(8))
        root.addView(title)
        tabs = TabLayout(this)
        tabs!!.setTabMode(TabLayout.MODE_FIXED)
        root.addView(tabs)
        val pages = LinearLayout(this)
        pages.setOrientation(LinearLayout.VERTICAL)
        root.addView(pages, LayoutParams(-1, 0, 1f))
        val connectionBody = page(pages)
        val audioBody = page(pages)
        val tuningBody = page(pages)
        val diagnosticsBody = page(pages)
        val tabNames =
            intArrayOf(
                R.string.tab_connection,
                R.string.tab_audio,
                R.string.tab_tuning,
                R.string.tab_diagnostics,
            )
        for (name in tabNames) tabs!!.addTab(tabs!!.newTab().setText(name))
        tabs!!.addOnTabSelectedListener(
            object : OnTabSelectedListener {
                override fun onTabSelected(tab: Tab) {
                    for (i in 0 until pages.getChildCount()) pages
                        .getChildAt(i)
                        .setVisibility(if (i == tab.getPosition()) View.VISIBLE else View.GONE)
                    if (captureTiming != null && tab.getPosition() == 3) updatePipeline()
                }

                override fun onTabUnselected(tab: Tab?) {}

                override fun onTabReselected(tab: Tab?) {}
            }
        )
        pages.getChildAt(1).setVisibility(View.GONE)
        pages.getChildAt(2).setVisibility(View.GONE)
        pages.getChildAt(3).setVisibility(View.GONE)
        tabs!!.selectTab(
            tabs!!.getTabAt(if (state == null) 0 else max(0, min(3, state.getInt("tab", 0))))
        )
        var body: LinearLayout? = connectionBody
        body!!.addView(label(getString(R.string.explanation), 16))
        status = label(getString(R.string.idle), 18)
        body!!.addView(card(status))
        timingStatus = label("", 14)
        body!!.addView(timingStatus)
        systemStatus = label("", 14)
        body!!.addView(systemStatus)
        body = audioBody
        val modeControls = LinearLayout(this)
        modeControls.setOrientation(LinearLayout.VERTICAL)
        modeSelection = label("", 18)
        modeControls.addView(modeSelection)
        val modes = MaterialButtonToggleGroup(this)
        modes.setSingleSelection(true)
        modes.setSelectionRequired(true)
        val selectedModeId = View.generateViewId()
        val wholeModeId = View.generateViewId()
        for (modeId in intArrayOf(selectedModeId, wholeModeId)) {
            val mode = MaterialButton(this)
            mode.setId(modeId)
            mode.setText(
                if (modeId == selectedModeId) R.string.mode_selected else R.string.mode_whole
            )
            mode.setBackgroundTintList(getColorStateList(R.color.mode_background))
            mode.setTextColor(getColorStateList(R.color.mode_text))
            mode.setStrokeColor(getColorStateList(R.color.mode_stroke))
            mode.setIconTint(getColorStateList(R.color.mode_text))
            mode.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START)
            mode.setIconPadding(dp(6))
            mode.setMinHeight(dp(48))
            mode.setLayoutParams(LayoutParams(0, -2, 1f))
            modes.addView(mode)
        }
        modes.check(if (StreamSettings.load(this)!!.wholeDevice) wholeModeId else selectedModeId)
        updateModeSelection(modes)
        modes.addOnButtonCheckedListener(
            OnButtonCheckedListener { group: MaterialButtonToggleGroup?, id: Int, checked: Boolean
                ->
                if (!checked) return@OnButtonCheckedListener
                val previous = StreamSettings.load(this)
                previous!!.withWholeDevice(id == wholeModeId)!!.save(this)
                updateModeSelection(group!!)
                if (BridgeState.capture != null) BridgeState.capture!!.reconfigure()
            }
        )
        modeControls.addView(modes)
        modeControls.addView(label(getString(R.string.mode_description), 14))
        body.addView(card(modeControls))
        body = connectionBody
        share =
            button(
                getString(R.string.enable),
                android.view.View.OnClickListener { view: View? ->
                    if (BridgeState.capture != null) {
                        stopService(Intent(this, CaptureService::class.java))
                        return@OnClickListener
                    }
                    requestSharing()
                },
            )
        body.addView(share)
        (share as MaterialButton).setBackgroundTintList(
            ColorStateList(
                arrayOf<IntArray?>(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(
                    ColorUtils.setAlphaComponent(textColor, 31),
                    MaterialColors.getColor(share!!, androidx.appcompat.R.attr.colorPrimary),
                ),
            )
        )
        share!!.setTextColor(
            ColorStateList(
                arrayOf<IntArray?>(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(
                    ColorUtils.setAlphaComponent(textColor, 97),
                    MaterialColors.getColor(
                        share!!,
                        com.google.android.material.R.attr.colorOnPrimary,
                    ),
                ),
            )
        )
        (share as MaterialButton).setStrokeWidth(0)
        val speakerHeader = LinearLayout(this)
        speakerHeader.setGravity(Gravity.CENTER_VERTICAL)
        val speakerTitle = label(getString(R.string.speaker_section), 18)
        speakerTitle.setTypeface(null, Typeface.BOLD)
        ViewCompat.setAccessibilityHeading(speakerTitle, true)
        speakerHeader.addView(speakerTitle, LayoutParams(0, -2, 1f))
        val search = MaterialButton(this)
        search.setText(R.string.speaker_search)
        search.setContentDescription(getString(R.string.refresh))
        search.setAllCaps(false)
        search.setTextColor(
            controlColors(
                MaterialColors.getColor(search, androidx.appcompat.R.attr.colorPrimary),
                97,
            )
        )
        search.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT))
        search.setStrokeWidth(0)
        search.setMinHeight(dp(48))
        search.setIconResource(R.drawable.ic_search)
        search.setIconSize(dp(18))
        search.setIconTint(search.getTextColors())
        search.setOnClickListener(android.view.View.OnClickListener { view: View? -> refresh() })
        speakerHeader.addView(search, LayoutParams(-2, -2))
        body.addView(speakerHeader)
        speakerHint = label("", 14)
        body.addView(speakerHint)
        speakers = LinearLayout(this)
        speakers!!.setOrientation(LinearLayout.VERTICAL)
        body.addView(speakers)
        val volumeControls = LinearLayout(this)
        volumeControls.setOrientation(LinearLayout.VERTICAL)
        volumeLabel = label(getString(R.string.volume, 0), 18)
        volumeControls.addView(volumeLabel)
        volumeHint = label(getString(R.string.volume_unavailable_hint), 14)
        volumeControls.addView(volumeHint)
        volume = Slider(this)
        volume!!.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES)
        volume!!.setValueFrom(0f)
        volume!!.setValueTo((BuildConfig.VOLUME_LIMIT).toFloat())
        volume!!.setStepSize(1f)
        volume!!.setLabelFormatter(
            LabelFormatter { value: Float -> Math.round(value).toString() + "%" }
        )
        volume!!.addOnChangeListener(
            OnChangeListener { slider: Slider?, value: Float, fromUser: Boolean ->
                if (!fromUser || !BridgeState.connected) return@OnChangeListener
                BridgeState.volume = VolumePolicy.apply(Math.round(value))
                NativeSender.volume(BridgeState.volume)
                BridgeState.publish()
            }
        )
        volumeControls.addView(volume)
        exactVolume =
            button(
                getString(R.string.volume_exact),
                android.view.View.OnClickListener { view: View? -> exactVolume() },
            )
        volumeControls.addView(exactVolume)
        body.addView(card(volumeControls))
        body.addView(
            button(
                getString(R.string.open_music),
                android.view.View.OnClickListener { view: View? ->
                    val music = getPackageManager().getLaunchIntentForPackage(BridgeState.MUSIC)
                    if (music == null) toast(R.string.music_missing) else startActivity(music)
                },
            )
        )
        body = audioBody
        val latencyControls = LinearLayout(this)
        latencyControls.setOrientation(LinearLayout.VERTICAL)
        profileSelection = label("", 18)
        latencyControls.addView(profileSelection)
        profileButton =
            button(
                getString(R.string.profile_choose),
                android.view.View.OnClickListener { view: View? -> chooseProfile() },
            )
        latencyControls.addView(profileButton)
        profileDetails = label("", 14)
        latencyControls.addView(profileDetails)
        captureBackendStatus = label("", 14)
        latencyControls.addView(captureBackendStatus)
        latencyControls.addView(label(getString(R.string.profiles_description), 14))
        body.addView(card(latencyControls))
        body = tuningBody
        body.addView(label(getString(R.string.manual_description), 16))
        body.addView(
            button(
                getString(R.string.manual_settings),
                android.view.View.OnClickListener { view: View? -> manualSettings() },
            )
        )
        body.addView(
            button(
                getString(R.string.network_settings),
                android.view.View.OnClickListener { view: View? -> networkSettings() },
            )
        )
        manualSummary = label("", 14)
        body.addView(manualSummary)
        receiverTimingSelection = label("", 14)
        body.addView(receiverTimingSelection)
        body.addView(label(getString(R.string.notes), 14))
        body.addView(
            button(
                getString(R.string.licenses),
                android.view.View.OnClickListener { view: View? -> licenses() },
            )
        )
        body = diagnosticsBody
        diagnosticsEmpty = label(getString(R.string.diagnostics_idle), 16)
        diagnosticsEmptyCard = card(diagnosticsEmpty)
        body.addView(diagnosticsEmptyCard)
        connectionTiming = label("", 16)
        connectionTimingCard = card(connectionTiming)
        body.addView(connectionTimingCard)
        diagnosticsDetails = LinearLayout(this)
        diagnosticsDetails!!.setOrientation(LinearLayout.VERTICAL)
        body.addView(diagnosticsDetails)
        body = diagnosticsDetails
        body!!.addView(label(getString(R.string.diagnostics_intro), 14))
        captureTiming = label("", 16)
        body!!.addView(card(captureTiming))
        phoneBufferTiming = label("", 16)
        body!!.addView(card(phoneBufferTiming))
        pipeTiming = label("", 16)
        body!!.addView(card(pipeTiming))
        senderTiming = label("", 16)
        body!!.addView(card(senderTiming))
        handoffTiming = label("", 16)
        body!!.addView(card(handoffTiming))
        networkTiming = label("", 16)
        body!!.addView(card(networkTiming))
        body!!.addView(card(label(getString(R.string.diagnostics_unmeasured), 14)))
        syncSettings()
        updateVolumeControls()
        updatePipeline()
        updateSpeakers()
        setContentView(root)
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putInt("tab", tabs!!.getSelectedTabPosition())
        super.onSaveInstanceState(state)
    }

    private fun page(parent: LinearLayout): LinearLayout {
        val scroll = ScrollView(this)
        scroll.setFillViewport(true)
        val content = LinearLayout(this)
        content.setOrientation(LinearLayout.VERTICAL)
        content.setPadding(dp(24), dp(8), dp(24), dp(24))
        scroll.addView(content)
        parent.addView(scroll, LayoutParams(-1, -1))
        return content
    }

    private fun chooseProfile() {
        val settings = StreamSettings.load(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_choose)
            .setSingleChoiceItems(
                arrayOf<String?>(
                    getString(R.string.profile_balanced),
                    getString(R.string.low_latency_mode),
                    getString(R.string.high_quality_mode),
                    getString(R.string.profile_manual),
                ),
                settings!!.profile(),
                android.content.DialogInterface.OnClickListener {
                    dialog: DialogInterface?,
                    which: Int ->
                    applySettings(
                        if (which == 3) StreamSettings.savedManual(this)
                        else StreamSettings.load(this)!!.preset(which)!!
                    )
                    dialog!!.dismiss()
                },
            )
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun manualSettings() {
        ManualSettingsDialog.show(
            this,
            StreamSettings.savedManual(this)!!,
            Consumer { settings: StreamSettings -> this.applySettings(settings) },
        )
    }

    private fun applySettings(settings: StreamSettings) {
        settings.save(this)
        syncSettings()
        if (BridgeState.capture != null) BridgeState.capture!!.reconfigure()
    }

    private fun syncSettings() {
        val settings = StreamSettings.load(this)
        profileSelection!!.setText(
            getString(
                R.string.profile_selected,
                getString(
                    if (settings!!.lowLatency) R.string.low_latency_mode
                    else if (settings!!.highQuality) R.string.high_quality_mode
                    else if (settings!!.manualMode) R.string.profile_manual
                    else R.string.profile_balanced
                ),
            )
        )
        if (settings!!.effectiveAdaptiveBuffer()) {
            profileDetails!!.setText(
                getString(
                    R.string.profile_values_adaptive,
                    settings!!.captureUnitMs(),
                    settings!!.requestedPresentationMs(),
                )
            )
        } else
            profileDetails!!.setText(
                getString(
                    R.string.profile_values,
                    settings!!.effectiveBufferMs(),
                    settings!!.captureUnitMs(),
                    settings!!.requestedPresentationMs(),
                )
            )
        val manual = StreamSettings.savedManual(this)
        manualSummary!!.setText(
            getString(
                R.string.manual_saved_summary,
                manual!!.phoneTiming!!.actualPeriodMs(),
                manual!!.phoneTiming!!.reserveMs(),
                manual!!.effectiveBufferMs(),
                manual!!.requestedPacketFrames(),
            )
        )
        receiverTimingSelection!!.setText(
            getString(
                if (settings.receiverTiming!!.independent) R.string.receiver_timing_selected
                else R.string.receiver_timing_linked,
                settings!!.requestedReceiverMinimumMs(),
                settings!!.requestedReceiverMaximumMs(),
                settings!!.requestedPresentationMs(),
            )
        )
    }

    override fun onStart() {
        super.onStart()
        RootConnector.request(this)
        BridgeState.observe(this)
        if (
            (checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") ==
                PackageManager.PERMISSION_GRANTED) || Build.VERSION.SDK_INT < 37
        )
            refresh()
    }

    override fun onStop() {
        sharingAttempt++
        awaitingSystemBridge = false
        sharingHandler.removeCallbacksAndMessages(null)
        BridgeState.remove(this)
        if (router != null) {
            router!!.unregisterRouteCallback(routes)
            router = null
        }
        super.onStop()
    }

    private fun refresh() {
        if (
            Build.VERSION.SDK_INT >= 37 &&
                (checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") !=
                    PackageManager.PERMISSION_GRANTED)
        ) {
            requestPermissions(arrayOf<String>("android.permission.ACCESS_LOCAL_NETWORK"), 3)
            return
        }
        if (router == null) {
            router = MediaRouter2.getInstance(this)
            router!!.registerRouteCallback(
                getMainExecutor(),
                routes,
                Builder(List.of<String?>(HomePodRoutes.FEATURE), true).build(),
            )
        }
        HomePodRoutes.ensureDiscovery()
        if (BridgeState.receivers!!.isEmpty()) {
            BridgeState.status = R.string.searching
            changed()
        }
    }

    private fun requestSharing() {
        if (
            Build.VERSION.SDK_INT >= 37 &&
                (checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") !=
                    PackageManager.PERMISSION_GRANTED)
        ) {
            requestPermissions(arrayOf<String>("android.permission.ACCESS_LOCAL_NETWORK"), 4)
            return
        }
        if (awaitingSystemBridge) return
        val attempt: Long = ++sharingAttempt
        awaitingSystemBridge = true
        RootConnector.request(this)
        changed()
        awaitSystemSharing(attempt, SystemClock.uptimeMillis() + 3000)
    }

    private fun awaitSystemSharing(attempt: Long, deadline: Long) {
        if (attempt != sharingAttempt || isFinishing() || isDestroyed()) return
        if (RootConnector.available()) {
            awaitingSystemBridge = false
            changed()
            startForegroundService(
                Intent(this, CaptureService::class.java).putExtra("system", true)
            )
            return
        }
        if (SystemClock.uptimeMillis() < deadline) {
            sharingHandler.postDelayed(Runnable { awaitSystemSharing(attempt, deadline) }, 50)
            return
        }
        awaitingSystemBridge = false
        changed()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.system_unavailable_title)
            .setMessage(R.string.system_unavailable_description)
            .setPositiveButton(
                R.string.system_retry,
                android.content.DialogInterface.OnClickListener { _, which: Int ->
                    requestSharing()
                },
            )
            .setNeutralButton(
                R.string.standard_sharing,
                android.content.DialogInterface.OnClickListener { _, which: Int ->
                    requestStandardSharing()
                },
            )
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestStandardSharing() {
        try {
            if (!StreamSettings.load(this)!!.wholeDevice)
                getPackageManager().getApplicationInfo(BridgeState.MUSIC, 0)
        } catch (e: NameNotFoundException) {
            toast(R.string.music_missing)
            return
        }
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf<String?>(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                ),
                2,
            )
            return
        }
        val manager = getSystemService<MediaProjectionManager?>(MediaProjectionManager::class.java)
        startActivityForResult(
            manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            ),
            1,
        )
    }

    override fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<out String>,
        granted: IntArray,
    ) {
        super.onRequestPermissionsResult(code, permissions!!, granted)
        if (granted.size == 0 || granted[0] != PackageManager.PERMISSION_GRANTED) {
            toast(R.string.permission_needed)
            return
        }
        if (code == 2) requestStandardSharing()
        else if (code == 3) refresh() else if (code == 4) requestSharing()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != Activity.RESULT_OK || data == null) return
        startForegroundService(
            Intent(this, CaptureService::class.java)
                .putExtra("consent", data)
                .putExtra("result", resultCode)
        )
    }

    override fun changed() {
        timingChanged()
        if (status == null) return
        syncSettings()
        updateConnectionStatus()
        share!!.setText(
            if (awaitingSystemBridge) R.string.preparing_sharing
            else if (BridgeState.capture == null) R.string.enable else R.string.stop
        )
        share!!.setEnabled(!awaitingSystemBridge)
        systemStatus!!.setText(
            if (awaitingSystemBridge) R.string.system_connecting
            else if (RootConnector.available()) R.string.system_ready else R.string.system_missing
        )
        updateVolumeControls()
        updateSpeakers()
    }

    private fun updateSpeakers() {
        val ready: Boolean = BridgeState.capture != null && !awaitingSystemBridge
        speakerHint!!.setText(
            if (ready) R.string.speaker_choose_hint else R.string.speaker_enable_hint
        )
        speakerHint!!.setVisibility(
            if (BridgeState.receivers!!.isEmpty() || BridgeState.selected != null) View.GONE
            else View.VISIBLE
        )
        speakers!!.removeAllViews()
        if (BridgeState.receivers!!.isEmpty()) {
            speakers!!.addView(card(label(getString(R.string.speaker_empty), 16)))
            return
        }
        for (receiver in BridgeState.receivers!!) {
            val selected =
                receiver!!
                    .id
                    .equals(if (BridgeState.selected == null) null else BridgeState.selected!!.id)
            val connected: Boolean = selected && BridgeState.connected
            val row = LinearLayout(this)
            row.setGravity(Gravity.CENTER_VERTICAL)
            val icon = ImageView(this)
            icon.setImageResource(R.drawable.ic_speaker)
            icon.setImageTintList(ColorStateList.valueOf(textColor))
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
            val iconLayout = LayoutParams(dp(32), dp(32))
            iconLayout.setMarginEnd(dp(16))
            row.addView(icon, iconLayout)
            val details = LinearLayout(this)
            details.setOrientation(LinearLayout.VERTICAL)
            val name = label(receiver!!.name, 18)
            name.setTypeface(null, Typeface.BOLD)
            name.setPadding(0, dp(8), 0, dp(2))
            details.addView(name)
            val state =
                label(
                    getString(
                        if (connected) R.string.speaker_connected
                        else if (selected) R.string.speaker_connecting
                        else R.string.speaker_available
                    ),
                    14,
                )
            state.setPadding(0, 0, 0, dp(8))
            if (selected)
                state.setTextColor(
                    MaterialColors.getColor(state, androidx.appcompat.R.attr.colorPrimary)
                )
            details.addView(state)
            val detailsLayout = LayoutParams(0, -2, 1f)
            detailsLayout.setMarginEnd(dp(12))
            row.addView(details, detailsLayout)
            if (!selected) {
                val connect =
                    button(
                        getString(R.string.speaker_connect),
                        android.view.View.OnClickListener { view: View? ->
                            connectSpeaker(receiver!!)
                        },
                    )
                connect.setContentDescription(
                    getString(R.string.speaker_connect_description, receiver!!.name)
                )
                connect.setEnabled(ready)
                connect.setMinWidth(dp(64))
                connect.setMinHeight(dp(48))
                connect.setPadding(dp(12), dp(8), dp(12), dp(8))
                row.addView(connect, LayoutParams(-2, -2))
            } else if (connected) {
                val check = ImageView(this)
                check.setImageResource(R.drawable.ic_check)
                check.setImageTintList(
                    ColorStateList.valueOf(
                        MaterialColors.getColor(check, androidx.appcompat.R.attr.colorPrimary)
                    )
                )
                check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
                row.addView(check, LayoutParams(dp(24), dp(24)))
            }
            val device = card(row)
            if (selected) {
                device.setStrokeWidth(dp(2))
                device.setStrokeColor(
                    MaterialColors.getColor(device, androidx.appcompat.R.attr.colorPrimary)
                )
            }
            speakers!!.addView(device)
        }
    }

    private fun connectSpeaker(receiver: Receiver) {
        if (BridgeState.capture == null || awaitingSystemBridge) return
        if (HomePodRoutes.available()) {
            HomePodRoutes.selectFromSystem(BridgeState.MUSIC, receiver.id)
        } else {
            BridgeState.capture!!.select(
                receiver,
                CaptureService.ConnectionCallback { success: Boolean ->
                    if (!success) {
                        val reason = BridgeState.receiverRejection!!.message(this, false)
                        Toast.makeText(
                                this,
                                if (reason!!.isEmpty()) getString(R.string.connection_failed)
                                else reason,
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                },
            )
        }
    }

    private fun updateConnectionStatus() {
        if (status == null) return
        val message: String?
        if (BridgeState.selected != null && BridgeState.status == R.string.connecting)
            message = getString(R.string.connecting, BridgeState.selected!!.name)
        else if (BridgeState.selected != null && BridgeState.status == R.string.playing)
            message = getString(R.string.playing, BridgeState.selected!!.name)
        else if (!BridgeState.receivers!!.isEmpty() && BridgeState.status == R.string.searching)
            message = getString(R.string.ready)
        else message = getString(BridgeState.status)
        val reason = BridgeState.receiverRejection!!.message(this, BridgeState.connected)
        status!!.setText(if (reason!!.isEmpty()) message else message + "\n\n" + reason)
    }

    private fun updateVolumeControls() {
        val connected = BridgeState.connected
        val title: String? =
            if (connected) getString(R.string.volume, BridgeState.volume)
            else getString(R.string.volume_unavailable)
        volumeLabel!!.setText(title)
        volumeLabel!!.setTextColor(
            if (connected) textColor else ColorUtils.setAlphaComponent(textColor, 97)
        )
        volumeHint!!.setVisibility(if (connected) View.GONE else View.VISIBLE)
        volume!!.setContentDescription(
            if (connected) title else title + ". " + getString(R.string.volume_unavailable_hint)
        )
        volume!!.setValue((if (connected) VolumePolicy.apply(BridgeState.volume) else 0).toFloat())
        volume!!.setEnabled(connected)
        exactVolume!!.setEnabled(connected)
    }

    override fun timingChanged() {
        updateConnectionStatus()
        if (captureTiming == null) return
        if (tabs!!.getSelectedTabPosition() == 3) updatePipeline()
        updateTargetTiming()
    }

    private fun updateTargetTiming() {
        val timing = BridgeState.timing
        val showTiming: Boolean = BridgeState.connected && timing != null
        timingStatus!!.setVisibility(if (showTiming) View.VISIBLE else View.GONE)
        if (showTiming) {
            val minimum: String? =
                if (timing[2] < 0) getString(R.string.latency_unknown)
                else getString(R.string.latency_ms, timing[2])
            timingStatus!!.setText(
                (getString(R.string.latency_active, timing[1], timing[0], minimum) +
                    (if (timing.size >= 8)
                        "\n" +
                            getString(
                                R.string.receiver_timing_active,
                                timing[4],
                                timing[5],
                                if (timing[6] < 0) getString(R.string.latency_unknown)
                                else getString(R.string.latency_ms, timing[6]),
                            )
                    else "") +
                    (if (timing.size >= 8 && timing[7] != 0)
                        "\n" + getString(R.string.receiver_timing_explicit)
                    else "") +
                    (if (timing[3] != 0) "\n" + getString(R.string.latency_fallback) else ""))
            )
        }
    }

    private fun networkSettings() {
        val selected = intArrayOf(if (NetworkSettings.load(this)!!.priority) 1 else 0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.network_settings)
            .setSingleChoiceItems(
                arrayOf<String?>(
                    getString(R.string.network_normal),
                    getString(R.string.network_priority),
                ),
                selected[0],
                android.content.DialogInterface.OnClickListener { _, which: Int ->
                    selected[0] = which
                },
            )
            .setPositiveButton(
                R.string.apply_settings,
                android.content.DialogInterface.OnClickListener { _, which: Int ->
                    NetworkSettings(selected[0] == 1).save(this)
                    if (BridgeState.capture != null) BridgeState.capture!!.reconfigure()
                },
            )
            .setNeutralButton(
                R.string.network_details,
                android.content.DialogInterface.OnClickListener { _, which: Int ->
                    MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.network_priority_description)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                },
            )
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun millis(micros: Long): String? {
        return if (micros < 0) getString(R.string.diagnostics_unknown)
        else getString(R.string.diagnostics_ms, micros / 1000.0)
    }

    private fun framesTime(frames: Long): String? {
        return if (frames < 0) getString(R.string.diagnostics_unknown)
        else millis(frames * 1000000L / 44100)
    }

    private fun updatePipeline() {
        val connection = BridgeState.connectionTiming
        val t: PipelineTiming? = if (BridgeState.connected) BridgeState.pipeline else null
        val connecting: Boolean = BridgeState.selected != null && !BridgeState.connected
        diagnosticsEmptyCard!!.setVisibility(if (t == null) View.VISIBLE else View.GONE)
        diagnosticsEmpty!!.setText(
            if (BridgeState.connected) R.string.diagnostics_waiting
            else if (connecting) R.string.diagnostics_connecting else R.string.diagnostics_idle
        )
        diagnosticsDetails!!.setVisibility(if (t == null) View.GONE else View.VISIBLE)
        connectionTimingCard!!.setVisibility(
            if (connection != null && (BridgeState.connected || connecting)) View.VISIBLE
            else View.GONE
        )
        connectionTiming!!.setText(
            if (connection == null) getString(R.string.diagnostics_idle)
            else
                getString(
                    R.string.diagnostics_connection,
                    millis(
                        connection.micros(
                            ConnectionTiming.Stage.SELECTED,
                            ConnectionTiming.Stage.READY,
                        )
                    ),
                    millis(
                        connection.micros(
                            ConnectionTiming.Stage.SELECTED,
                            ConnectionTiming.Stage.WORKER,
                        )
                    ),
                    millis(
                        connection.micros(
                            ConnectionTiming.Stage.WORKER,
                            ConnectionTiming.Stage.NETWORK,
                        )
                    ),
                    millis(
                        connection.micros(
                            ConnectionTiming.Stage.NETWORK,
                            ConnectionTiming.Stage.CAPTURE,
                        )
                    ),
                    millis(
                        connection.micros(
                            ConnectionTiming.Stage.SENDER,
                            ConnectionTiming.Stage.AUTHENTICATED,
                        )
                    ),
                )
        )
        if (captureBackendStatus != null)
            captureBackendStatus!!.setText(
                if (BridgeState.selected == null) R.string.diagnostics_idle
                else
                    when (BridgeState.captureBackend!!) {
                        BridgeState.CaptureBackend.STANDARD -> R.string.capture_backend_standard
                        BridgeState.CaptureBackend.CHECKING -> R.string.capture_backend_checking
                        BridgeState.CaptureBackend.DEDICATED -> R.string.capture_backend_active
                        BridgeState.CaptureBackend.DEDICATED_SHORT -> R.string.capture_backend_short
                        BridgeState.CaptureBackend.DEDICATED_TEST -> R.string.capture_backend_test
                        BridgeState.CaptureBackend.FALLBACK -> R.string.capture_backend_fallback
                    }
            )
        if (
            BridgeState.captureBackend == BridgeState.CaptureBackend.DEDICATED_SHORT &&
                BridgeState.selected != null &&
                BridgeState.activePhoneTiming != null
        )
            captureBackendStatus!!.setText(
                getString(
                    R.string.capture_backend_period,
                    BridgeState.activePhoneTiming!!.actualPeriodMs(),
                )
            )
        val live: Boolean =
            BridgeState.captureBackend == BridgeState.CaptureBackend.DEDICATED ||
                BridgeState.captureBackend == BridgeState.CaptureBackend.DEDICATED_SHORT
        if (t == null) return
        val request =
            getString(
                if (t.capture(9) == 1L) R.string.capture_fast_requested
                else if (t.capture(9) == 2L) R.string.capture_fast_fallback
                else if (t.capture(9) == 0L) R.string.capture_standard
                else R.string.diagnostics_unknown
            )
        val path = BridgeState.phonePath
        phoneBufferTiming!!.setText(
            if (live)
                getString(
                    R.string.device_buffer_dedicated_target,
                    framesTime(
                        (if (
                                BridgeState.captureBackend ==
                                    BridgeState.CaptureBackend.DEDICATED_SHORT
                            )
                                if (BridgeState.activePhoneTiming == null) 800
                                else BridgeState.activePhoneTiming!!.reserveFrames
                            else 800)
                            .toLong()
                    ),
                )
            else if (t.halQueuedFrames >= 0) getString(R.string.device_buffer_hal_test)
            else if (path == null) getString(R.string.device_buffer_off)
            else
                when (path.state!!) {
                    OwnedAudioPath.State.CHECKING -> getString(R.string.device_buffer_checking)
                    OwnedAudioPath.State.ACTIVE ->
                        getString(
                            R.string.device_buffer_active,
                            framesTime((path.targetFrames).toLong()),
                        )
                    OwnedAudioPath.State.STANDARD -> getString(R.string.device_buffer_standard)
                    OwnedAudioPath.State.RECOVERY_REQUIRED ->
                        getString(R.string.device_buffer_recovering)
                }
        )
        captureTiming!!.setText(
            if (live)
                (if (t.halQueuedFrames < 0) getString(R.string.diagnostics_hal_idle)
                else
                    getString(
                        R.string.diagnostics_hal_capture_live,
                        framesTime(t.halQueuedFrames),
                        framesTime(BufferLimits.DEDICATED_FRAMES.toLong()),
                    ))
            else if (t.halQueuedFrames >= 0)
                getString(R.string.diagnostics_hal_capture, framesTime(t.halQueuedFrames))
            else
                getString(
                    R.string.diagnostics_capture,
                    millis(if (t.captureAgeFresh()) t.capture(1) else -1),
                    millis(t.capture(3)),
                    millis(t.capture(4)),
                    framesTime(t.capture(8)),
                    framesTime(t.capture(7)),
                    request,
                )
        )
        pipeTiming!!.setText(
            getString(
                R.string.diagnostics_pipe,
                millis(t.pipeMicros),
                millis(t.capture(5)),
                millis(t.capture(6)),
            )
        )
        senderTiming!!.setText(
            getString(
                R.string.diagnostics_sender,
                framesTime(t.sender(0)),
                framesTime(t.sender(1)),
                millis(t.sender(2)),
                framesTime(t.sender(10)),
                framesTime(t.sender(7)),
            )
        )
        // Negative lead is meaningful lateness, unlike an unavailable positive duration.
        val last: String? =
            if (t.sender(6) > 0) getString(R.string.diagnostics_ms, t.sender(4) / 1000.0)
            else getString(R.string.diagnostics_unknown)
        val minimum: String? =
            if (t.sender(6) > 0) getString(R.string.diagnostics_ms, t.sender(3) / 1000.0)
            else getString(R.string.diagnostics_unknown)
        val targets = BridgeState.timing
        handoffTiming!!.setText(
            getString(
                R.string.diagnostics_handoff,
                last,
                minimum,
                max(0L, t.sender(5)),
                max(0L, t.sender(6)),
                if (targets == null) getString(R.string.diagnostics_unknown)
                else millis(targets[1] * 1000L),
            )
        )
        val network: NetworkTiming =
            (if (BridgeState.networkTiming == null) NetworkTiming(null, 0)
            else BridgeState.networkTiming)!!
        networkTiming!!.setText(
            (getString(R.string.diagnostics_network, millis(t.sender(11)), millis(t.sender(12))) +
                "\n\n" +
                getString(
                    R.string.diagnostics_network_tx,
                    millis(network.transit(6)),
                    millis(network.transit(7)),
                    millis(network.transit(8)),
                    if (network.value(3) < 0) getString(R.string.diagnostics_unknown)
                    else network.value(3).toString(),
                    max(0L, network.value(1)),
                    max(0L, network.value(0)),
                    max(0L, network.value(5)),
                    getString(network.priorityResource()),
                    getString(network.wifiResource()),
                    max(0L, network.value(14)),
                    max(0L, network.value(15)),
                    max(0L, network.value(16)),
                ))
        )
    }

    private fun label(value: String?, size: Int): TextView {
        val text = TextView(this)
        text.setText(value)
        text.setTextSize(size.toFloat())
        TextViewCompat.setTextAppearance(
            text,
            if (size >= 28)
                com.google.android.material.R.style.TextAppearance_Material3_HeadlineLarge
            else if (size >= 18)
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
            else com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
        )
        text.setTextColor(textColor)
        text.setPadding(0, dp(12), 0, dp(12))
        text.setLineSpacing(dp(3).toFloat(), 1f)
        return text
    }

    private fun button(label: String?, listener: android.view.View.OnClickListener?): Button {
        val button = MaterialButton(this)
        button.setText(label)
        button.setAllCaps(false)
        button.setTextSize(16f)
        button.setTextColor(controlColors(textColor, 97))
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT))
        button.setStrokeWidth(dp(1))
        button.setStrokeColor(
            controlColors(
                MaterialColors.getColor(button, com.google.android.material.R.attr.colorOutline),
                31,
            )
        )
        button.setPadding(dp(20), dp(12), dp(20), dp(12))
        val layout = LayoutParams(-1, -2)
        layout.setMargins(0, dp(6), 0, dp(6))
        button.setLayoutParams(layout)
        button.setOnClickListener(listener)
        return button
    }

    private fun controlColors(color: Int, disabledAlpha: Int): ColorStateList? {
        return ColorStateList(
            arrayOf<IntArray?>(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(color, ColorUtils.setAlphaComponent(color, disabledAlpha)),
        )
    }

    private fun card(content: View?): MaterialCardView {
        val card = MaterialCardView(this)
        card.setCardBackgroundColor(cardColor)
        card.setStrokeWidth(0)
        card.setRadius(dp(24).toFloat())
        card.setContentPadding(dp(20), dp(8), dp(20), dp(8))
        val layout = LayoutParams(-1, -2)
        layout.setMargins(0, dp(8), 0, dp(8))
        card.setLayoutParams(layout)
        card.addView(content)
        return card
    }

    private fun licenses() {
        try {
            getAssets().open("licenses.txt").use { input ->
                val text = label(String(input.readAllBytes(), StandardCharsets.UTF_8), 13)
                text.setPadding(dp(20), dp(12), dp(20), dp(12))
                val scroll = ScrollView(this)
                scroll.addView(text)
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.licenses)
                    .setView(scroll)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        } catch (e: IOException) {
            toast(R.string.licenses)
        }
    }

    private fun exactVolume() {
        if (!BridgeState.connected) return
        val field = TextInputLayout(this)
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE)
        field.setHint(getString(R.string.volume_range))
        field.setPadding(dp(20), dp(8), dp(20), 0)
        val input = TextInputEditText(field.getContext())
        input.setInputType(InputType.TYPE_CLASS_NUMBER)
        input.setFilters(arrayOf<InputFilter>(LengthFilter(3)))
        input.setText(BridgeState.volume.toString())
        input.selectAll()
        field.addView(input, LayoutParams(-1, -2))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.volume_exact)
            .setView(field)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                R.string.apply_settings,
                android.content.DialogInterface.OnClickListener { _, which: Int ->
                    try {
                        val requested = input.getText().toString().toInt()
                        if (requested < 0 || requested > 100) throw NumberFormatException()
                        if (!BridgeState.connected) return@OnClickListener
                        BridgeState.volume = requested
                        NativeSender.volume(requested)
                        BridgeState.publish()
                    } catch (e: NumberFormatException) {
                        toast(R.string.volume_invalid)
                    }
                },
            )
            .show()
    }

    private fun updateModeSelection(group: MaterialButtonToggleGroup) {
        for (i in 0 until group.getChildCount()) {
            val mode: MaterialButton? = group.getChildAt(i) as MaterialButton?
            mode!!.setIconResource(if (mode!!.isChecked()) R.drawable.ic_check else 0)
            mode!!.setStrokeWidth(dp(if (mode!!.isChecked()) 2 else 1))
            mode!!.setTypeface(null, if (mode!!.isChecked()) Typeface.BOLD else Typeface.NORMAL)
            mode!!.setStateDescription(
                getString(if (mode!!.isChecked()) R.string.mode_active else R.string.mode_inactive)
            )
        }
        modeSelection!!.setText(
            getString(
                R.string.capture_mode,
                getString(
                    if (StreamSettings.load(this)!!.wholeDevice) R.string.mode_whole
                    else R.string.mode_selected
                ),
            )
        )
    }

    private fun dp(value: Int): Int {
        return Math.round(value * getResources().getDisplayMetrics().density)
    }

    private fun toast(string: Int) {
        Toast.makeText(this, string, Toast.LENGTH_LONG).show()
    }
}
