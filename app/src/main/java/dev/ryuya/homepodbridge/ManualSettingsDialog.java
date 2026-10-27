package dev.ryuya.homepodbridge;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import dev.ryuya.homepodbridge.halprobe.PhoneTiming;
import java.util.Locale;
import java.util.function.Consumer;

/** A single atomic manual form: cancel changes nothing and invalid input stays visible. */
final class ManualSettingsDialog {
  static void show(Context context, StreamSettings previous, Consumer<StreamSettings> apply) {
    StreamSettings saved = previous.manualMode() ? previous : previous.asManual();
    LinearLayout body = new LinearLayout(context);
    body.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
    body.setPadding(padding, 0, padding, padding);
    note(body, R.string.manual_description);
    int[] backend = {saved.dedicatedCapture() ? 1 : 0}, codec = {saved.compressedAudio() ? 1 : 0};
    MaterialButton route =
        choice(
            body,
            R.string.manual_backend,
            new int[] {R.string.manual_backend_standard, R.string.manual_backend_dedicated},
            backend);
    choice(
        body,
        R.string.manual_encoding,
        new int[] {R.string.manual_encoding_pcm, R.string.manual_encoding_alac},
        codec);
    TextInputLayout buffer =
        field(
            body, R.string.manual_send_buffer, Integer.toString(saved.effectiveBufferMs()), false);
    buffer.setHelperText(context.getString(R.string.manual_integer_range, 0, 300));
    TextInputLayout block =
        field(
            body, R.string.manual_capture_block, Integer.toString(saved.effectiveBlockMs()), false);
    block.setHelperText(context.getString(R.string.manual_integer_range, 2, 40));
    TextInputLayout period =
        field(body, R.string.manual_period, decimal(saved.phoneTiming().actualPeriodMs()), true);
    TextInputLayout reserve =
        field(body, R.string.manual_reserve, decimal(saved.phoneTiming().reserveMs()), true);
    reserve.setHelperText(context.getString(R.string.manual_reserve_help));
    TextView fixed = note(body, R.string.manual_dedicated_read);
    note(body, R.string.manual_observed_queue);
    TextInputLayout standardBuffer =
        field(
            body,
            R.string.manual_standard_buffer,
            saved.deviceBuffer() ? Integer.toString(saved.deviceBufferFrames()) : "0",
            false);
    standardBuffer.setHelperText(context.getString(R.string.manual_standard_buffer_help));
    TextInputLayout packet =
        field(
            body,
            R.string.manual_packet,
            Integer.toString(saved.packetFramesForBackend(saved.dedicatedCapture())),
            false);
    packet.setHelperText(context.getString(R.string.manual_packet_help));
    note(body, R.string.manual_receiver);
    TextInputLayout minimum =
        field(
            body,
            R.string.receiver_timing_minimum,
            Integer.toString(saved.requestedReceiverMinimumMs()),
            false);
    TextInputLayout maximum =
        field(
            body,
            R.string.receiver_timing_maximum,
            Integer.toString(saved.requestedReceiverMaximumMs()),
            false);
    TextInputLayout presentation =
        field(
            body,
            R.string.receiver_timing_presentation,
            Integer.toString(saved.requestedPresentationMs()),
            false);
    for (var field : new TextInputLayout[] {minimum, maximum, presentation})
      field.setHelperText(context.getString(R.string.manual_integer_range, 10, 2000));
    Runnable refresh =
        () -> {
          boolean dedicated = backend[0] == 1;
          period.setEnabled(dedicated);
          reserve.setEnabled(dedicated);
          fixed.setVisibility(dedicated ? View.VISIBLE : View.GONE);
          block.setEnabled(!dedicated);
          standardBuffer.setEnabled(!dedicated);
          packet.setEnabled(dedicated);
          try {
            PhoneTiming applied = PhoneTiming.nearest(number(period, 1.09, 21.04), 96);
            period.setHelperText(
                context.getString(
                    R.string.manual_period_applied,
                    applied.actualPeriodMs(),
                    applied.periodFrames()));
            period.setError(null);
          } catch (IllegalArgumentException invalid) {
            period.setHelperText(context.getString(R.string.manual_period_help));
          }
        };
    route.setTag(refresh);
    period
        .getEditText()
        .addTextChangedListener(
            new TextWatcher() {
              public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

              public void onTextChanged(CharSequence s, int start, int before, int count) {
                refresh.run();
              }

              public void afterTextChanged(Editable e) {}
            });
    refresh.run();
    ScrollView scroll = new ScrollView(context);
    scroll.addView(body);
    var dialog =
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.manual_settings)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.apply_settings, null)
            .create();
    dialog.setOnShowListener(
        shown ->
            dialog
                .getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(
                    view -> {
                      TextInputLayout[] all = {
                        buffer,
                        block,
                        period,
                        reserve,
                        standardBuffer,
                        packet,
                        minimum,
                        maximum,
                        presentation
                      };
                      for (var f : all) f.setError(null);
                      try {
                        int send = integer(buffer, 0, 300),
                            capture = backend[0] == 1 ? saved.blockMs() : integer(block, 2, 40);
                        PhoneTiming timing =
                            backend[0] == 1
                                ? PhoneTiming.nearest(
                                    number(period, 1.09, 21.04),
                                    (int) Math.round(number(reserve, 1.46, 18.14) * 44100 / 1000))
                                : saved.phoneTiming();
                        int standard = backend[0] == 1 ? 0 : integer(standardBuffer, 0, 1856);
                        if (standard != 0 && standard != 928 && standard != 1856)
                          throw invalid(standardBuffer, R.string.manual_standard_buffer_help);
                        int frames = backend[0] == 1 ? integer(packet, 44, 352) : 352;
                        if (frames != 44 && frames != 88 && frames != 352)
                          throw invalid(packet, R.string.manual_packet_help);
                        int min = integer(minimum, 10, 2000),
                            max = integer(maximum, 10, 2000),
                            offset = integer(presentation, 10, 2000);
                        if (max < min) throw invalid(maximum, R.string.receiver_timing_order);
                        StreamSettings value =
                            new StreamSettings(
                                saved.wholeDevice(),
                                send,
                                capture,
                                offset,
                                false,
                                false,
                                codec[0] == 1,
                                false,
                                standard != 0,
                                standard == 0 ? 1856 : standard,
                                backend[0] == 1,
                                new ReceiverTiming(true, min, max, offset),
                                frames != 352,
                                backend[0] == 1,
                                true,
                                timing,
                                frames == 44 ? 44 : 88);
                        apply.accept(value);
                        dialog.dismiss();
                      } catch (IllegalArgumentException invalid) {
                        /* Field owns the localized validation error. */
                      }
                    }));
    dialog.show();
  }

  private static double number(TextInputLayout field, double min, double max) {
    try {
      double value = Double.parseDouble(field.getEditText().getText().toString().replace(',', '.'));
      if (!Double.isFinite(value) || value < min || value > max) throw new NumberFormatException();
      return value;
    } catch (NumberFormatException bad) {
      throw invalid(field, R.string.manual_invalid);
    }
  }

  private static int integer(TextInputLayout field, int min, int max) {
    double value = number(field, min, max);
    if (value != Math.rint(value)) throw invalid(field, R.string.manual_invalid);
    return (int) value;
  }

  private static IllegalArgumentException invalid(TextInputLayout field, int error) {
    field.setError(field.getContext().getString(error));
    field.requestFocus();
    return new IllegalArgumentException();
  }

  private static String decimal(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  private static TextView note(LinearLayout body, int resource) {
    TextView view = new TextView(body.getContext());
    view.setText(resource);
    view.setPadding(0, 16, 0, 16);
    body.addView(view);
    return view;
  }

  private static TextInputLayout field(
      LinearLayout body, int title, String value, boolean decimal) {
    var field = new TextInputLayout(body.getContext());
    field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
    field.setHint(body.getContext().getString(title));
    var edit = new TextInputEditText(field.getContext());
    edit.setInputType(
        android.text.InputType.TYPE_CLASS_NUMBER
            | (decimal ? android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
    edit.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(8)});
    edit.setText(value);
    field.addView(edit, new LinearLayout.LayoutParams(-1, -2));
    body.addView(field);
    return field;
  }

  private static MaterialButton choice(LinearLayout body, int title, int[] labels, int[] selected) {
    var button = new MaterialButton(body.getContext());
    Runnable refresh =
        () ->
            button.setText(
                body.getContext()
                    .getString(title, body.getContext().getString(labels[selected[0]])));
    refresh.run();
    button.setOnClickListener(
        v -> {
          String[] names = new String[labels.length];
          for (int i = 0; i < labels.length; i++) names[i] = body.getContext().getString(labels[i]);
          new MaterialAlertDialogBuilder(body.getContext())
              .setSingleChoiceItems(
                  names,
                  selected[0],
                  (d, which) -> {
                    selected[0] = which;
                    refresh.run();
                    if (button.getTag() instanceof Runnable update) update.run();
                    d.dismiss();
                  })
              .setNegativeButton(android.R.string.cancel, null)
              .show();
        });
    body.addView(button);
    return button;
  }

  private ManualSettingsDialog() {}
}
