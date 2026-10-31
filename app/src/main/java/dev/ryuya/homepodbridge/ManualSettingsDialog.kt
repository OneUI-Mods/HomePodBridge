package dev.ryuya.homepodbridge

import android.content.Context
import android.content.DialogInterface
import android.content.DialogInterface.OnShowListener
import android.text.Editable
import android.text.InputFilter
import android.text.InputFilter.LengthFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.ryuya.homepodbridge.halprobe.PhoneTiming
import java.util.Locale
import java.util.function.Consumer
import kotlin.jvm.JvmStatic
import kotlin.math.round

/* A single atomic manual form: cancel changes nothing and invalid input stays visible. */
object ManualSettingsDialog {
    @JvmStatic
    fun show(context: Context, previous: StreamSettings, apply: Consumer<StreamSettings?>) {
        val saved: StreamSettings? = if (previous.manualMode) previous else previous.asManual()
        val body = LinearLayout(context)
        body.setOrientation(LinearLayout.VERTICAL)
        val padding = (20 * context.getResources().getDisplayMetrics().density).toInt()
        body.setPadding(padding, 0, padding, padding)
        ManualSettingsDialog.note(body, R.string.manual_description)
        val backend = intArrayOf(if (saved!!.dedicatedCapture) 1 else 0)
        val codec = intArrayOf(if (saved!!.compressedAudio) 1 else 0)
        val route =
            ManualSettingsDialog.choice(
                body,
                R.string.manual_backend,
                intArrayOf(R.string.manual_backend_standard, R.string.manual_backend_dedicated),
                backend,
            )
        ManualSettingsDialog.choice(
            body,
            R.string.manual_encoding,
            intArrayOf(R.string.manual_encoding_pcm, R.string.manual_encoding_alac),
            codec,
        )
        val buffer =
            ManualSettingsDialog.field(
                body,
                R.string.manual_send_buffer,
                saved!!.effectiveBufferMs().toString(),
                false,
            )
        buffer.setHelperText(context.getString(R.string.manual_integer_range, 0, 300))
        val block =
            ManualSettingsDialog.field(
                body,
                R.string.manual_capture_block,
                saved!!.effectiveBlockMs().toString(),
                false,
            )
        block.setHelperText(context.getString(R.string.manual_integer_range, 2, 40))
        val period =
            ManualSettingsDialog.field(
                body,
                R.string.manual_period,
                ManualSettingsDialog.decimal(saved.phoneTiming!!.actualPeriodMs()),
                true,
            )
        val reserve =
            ManualSettingsDialog.field(
                body,
                R.string.manual_reserve,
                ManualSettingsDialog.decimal(saved.phoneTiming!!.reserveMs()),
                true,
            )
        reserve.setHelperText(context.getString(R.string.manual_reserve_help))
        val fixed = ManualSettingsDialog.note(body, R.string.manual_dedicated_read)
        ManualSettingsDialog.note(body, R.string.manual_observed_queue)
        val standardBuffer =
            ManualSettingsDialog.field(
                body,
                R.string.manual_standard_buffer,
                if (saved.deviceBuffer) saved.deviceBufferFrames.toString() else "0",
                false,
            )
        standardBuffer.setHelperText(context.getString(R.string.manual_standard_buffer_help))
        val packet =
            ManualSettingsDialog.field(
                body,
                R.string.manual_packet,
                saved!!.packetFramesForBackend(saved.dedicatedCapture).toString(),
                false,
            )
        packet.setHelperText(context.getString(R.string.manual_packet_help))
        ManualSettingsDialog.note(body, R.string.manual_receiver)
        val minimum =
            ManualSettingsDialog.field(
                body,
                R.string.receiver_timing_minimum,
                saved!!.requestedReceiverMinimumMs().toString(),
                false,
            )
        val maximum =
            ManualSettingsDialog.field(
                body,
                R.string.receiver_timing_maximum,
                saved!!.requestedReceiverMaximumMs().toString(),
                false,
            )
        val presentation =
            ManualSettingsDialog.field(
                body,
                R.string.receiver_timing_presentation,
                saved!!.requestedPresentationMs().toString(),
                false,
            )
        for (field in arrayOf<TextInputLayout?>(minimum, maximum, presentation)) field!!
            .setHelperText(context.getString(R.string.manual_integer_range, 10, 2000))
        val refresh = Runnable {
            val dedicated: Boolean = backend[0] == 1
            period.setEnabled(dedicated)
            reserve.setEnabled(dedicated)
            fixed.setVisibility(if (dedicated) View.VISIBLE else View.GONE)
            block.setEnabled(!dedicated)
            standardBuffer.setEnabled(!dedicated)
            packet.setEnabled(dedicated)
            try {
                val applied =
                    PhoneTiming.nearest(ManualSettingsDialog.number(period, 1.09, 21.04), 96)
                period.setHelperText(
                    context.getString(
                        R.string.manual_period_applied,
                        applied!!.actualPeriodMs(),
                        applied!!.periodFrames(),
                    )
                )
                period.setError(null)
            } catch (invalid: IllegalArgumentException) {
                period.setHelperText(context.getString(R.string.manual_period_help))
            }
        }
        route.setTag(refresh)
        period
            .getEditText()!!
            .addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {
                        refresh.run()
                    }

                    override fun afterTextChanged(e: Editable?) {}
                }
            )
        refresh.run()
        val scroll = ScrollView(context)
        scroll.addView(body)
        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.manual_settings)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.apply_settings, null)
                .create()
        dialog.setOnShowListener(
            OnShowListener { shown: DialogInterface? ->
                dialog
                    .getButton(DialogInterface.BUTTON_POSITIVE)
                    .setOnClickListener(
                        android.view.View.OnClickListener { view: View? ->
                            val all =
                                arrayOf<TextInputLayout?>(
                                    buffer,
                                    block,
                                    period,
                                    reserve,
                                    standardBuffer,
                                    packet,
                                    minimum,
                                    maximum,
                                    presentation,
                                )
                            for (f in all) f!!.setError(null)
                            try {
                                val send = ManualSettingsDialog.integer(buffer, 0, 300)
                                val capture: Int =
                                    if (backend[0] == 1) saved.blockMs
                                    else ManualSettingsDialog.integer(block, 2, 40)
                                val timing: PhoneTiming? =
                                    if (backend[0] == 1)
                                        PhoneTiming.nearest(
                                            ManualSettingsDialog.number(period, 1.09, 21.04),
                                            Math.round(
                                                    ManualSettingsDialog.number(
                                                        reserve,
                                                        1.45,
                                                        18.14,
                                                    ) * 44100 / 1000
                                                )
                                                .toInt(),
                                        )
                                    else saved.phoneTiming
                                val standard: Int =
                                    if (backend[0] == 1) 0
                                    else ManualSettingsDialog.integer(standardBuffer, 0, 1856)
                                if (standard != 0 && standard != 928 && standard != 1856)
                                    throw ManualSettingsDialog.invalid(
                                        standardBuffer,
                                        R.string.manual_standard_buffer_help,
                                    )!!
                                val frames: Int =
                                    if (backend[0] == 1)
                                        ManualSettingsDialog.integer(packet, 44, 352)
                                    else 352
                                if (frames != 44 && frames != 88 && frames != 352)
                                    throw ManualSettingsDialog.invalid(
                                        packet,
                                        R.string.manual_packet_help,
                                    )!!
                                val min = ManualSettingsDialog.integer(minimum, 10, 2000)
                                val max = ManualSettingsDialog.integer(maximum, 10, 2000)
                                val offset = ManualSettingsDialog.integer(presentation, 10, 2000)
                                if (max < min)
                                    throw ManualSettingsDialog.invalid(
                                        maximum,
                                        R.string.receiver_timing_order,
                                    )!!
                                val value =
                                    StreamSettings(
                                        saved.wholeDevice,
                                        send,
                                        capture,
                                        offset,
                                        false,
                                        false,
                                        codec[0] == 1,
                                        false,
                                        standard != 0,
                                        if (standard == 0) 1856 else standard,
                                        backend[0] == 1,
                                        ReceiverTiming(true, min, max, offset),
                                        frames != 352,
                                        backend[0] == 1,
                                        true,
                                        timing,
                                        if (frames == 44) 44 else 88,
                                    )
                                apply.accept(value)
                                dialog.dismiss()
                            } catch (invalid: IllegalArgumentException) {
                                /* Field owns the localized validation error. */
                            }
                        }
                    )
            }
        )
        dialog.show()
    }

    @JvmStatic
    private fun number(field: TextInputLayout, min: Double, max: Double): Double {
        try {
            val value = field.getEditText()!!.getText().toString().replace(',', '.').toDouble()
            if (!java.lang.Double.isFinite(value) || value < min || value > max)
                throw NumberFormatException()
            return value
        } catch (bad: NumberFormatException) {
            throw ManualSettingsDialog.invalid(field, R.string.manual_invalid)!!
        }
    }

    @JvmStatic
    private fun integer(field: TextInputLayout, min: Int, max: Int): Int {
        val value = ManualSettingsDialog.number(field, min.toDouble(), max.toDouble())
        if (value != round(value))
            throw ManualSettingsDialog.invalid(field, R.string.manual_invalid)!!
        return value.toInt()
    }

    @JvmStatic
    private fun invalid(field: TextInputLayout, error: Int): IllegalArgumentException {
        field.setError(field.getContext().getString(error))
        field.requestFocus()
        return IllegalArgumentException()
    }

    @JvmStatic
    private fun decimal(value: Double): String? {
        return String.format(Locale.ROOT, "%.2f", value)
    }

    @JvmStatic
    private fun note(body: LinearLayout, resource: Int): TextView {
        val view = TextView(body.getContext())
        view.setText(resource)
        view.setPadding(0, 16, 0, 16)
        body.addView(view)
        return view
    }

    @JvmStatic
    private fun field(
        body: LinearLayout,
        title: Int,
        value: String?,
        decimal: Boolean,
    ): TextInputLayout {
        val field = TextInputLayout(body.getContext())
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE)
        field.setHint(body.getContext().getString(title))
        val edit = TextInputEditText(field.getContext())
        edit.setInputType(
            InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        )
        edit.setFilters(arrayOf<InputFilter>(LengthFilter(8)))
        edit.setText(value)
        field.addView(edit, LayoutParams(-1, -2))
        body.addView(field)
        return field
    }

    @JvmStatic
    private fun choice(
        body: LinearLayout,
        title: Int,
        labels: IntArray,
        selected: IntArray,
    ): MaterialButton {
        val button = MaterialButton(body.getContext())
        val refresh = Runnable {
            button.setText(
                body.getContext().getString(title, body.getContext().getString(labels[selected[0]]))
            )
        }
        refresh.run()
        button.setOnClickListener(
            android.view.View.OnClickListener { v: View? ->
                val names = arrayOfNulls<String>(labels.size)
                for (i in labels.indices) names[i] = body.getContext().getString(labels[i])
                MaterialAlertDialogBuilder(body.getContext())
                    .setSingleChoiceItems(
                        names,
                        selected[0],
                        android.content.DialogInterface.OnClickListener {
                            d: DialogInterface?,
                            which: Int ->
                            selected[0] = which
                            refresh.run()
                            run {
                                val update = button.getTag() as? Runnable
                                if (update != null) update.run()
                            }
                            d!!.dismiss()
                        },
                    )
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        )
        body.addView(button)
        return button
    }
}
