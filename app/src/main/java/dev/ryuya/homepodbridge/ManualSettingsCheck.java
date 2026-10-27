package dev.ryuya.homepodbridge;

import android.content.Context;

/** Fixed DUMP-protected persistence check; no capture, receiver or network is opened. */
final class ManualSettingsCheck {
  static String run(Context context) {
    StreamSettings before = StreamSettings.load(context);
    var manual = context.getSharedPreferences("manualStream", Context.MODE_PRIVATE);
    var snapshot = new java.util.HashMap<String, Object>(manual.getAll());
    boolean checked = false;
    try {
      var expected =
          before
              .asManual()
              .withPhoneTiming(new dev.ryuya.homepodbridge.halprobe.PhoneTiming(11, 447))
              .withBuffers(37, 7, 123)
              .withReceiverTiming(new ReceiverTiming(true, 25, 180, 123));
      expected.save(context);
      if (!expected.equals(StreamSettings.load(context)))
        throw new IllegalStateException("Manual round trip");
      for (int profile : new int[] {1, 2, 0}) {
        StreamSettings.load(context).preset(profile).save(context);
        if (!expected.equals(StreamSettings.savedManual(context)))
          throw new IllegalStateException("Manual snapshot changed");
      }
      checked = true;
    } finally {
      before.save(context);
      var editor = manual.edit().clear();
      snapshot.forEach(
          (key, value) -> {
            if (value instanceof Boolean b) editor.putBoolean(key, b);
            else if (value instanceof Integer i) editor.putInt(key, i);
            else throw new IllegalStateException("Unexpected setting type");
          });
      if (!editor.commit() || !before.equals(StreamSettings.load(context)))
        throw new IllegalStateException("Settings restoration unconfirmed");
    }
    return checked
        ? "MANUAL_SETTINGS_PASS restored=true presetSnapshots=true noPlayback=true"
        : "MANUAL_SETTINGS_FAIL";
  }

  private ManualSettingsCheck() {}
}
