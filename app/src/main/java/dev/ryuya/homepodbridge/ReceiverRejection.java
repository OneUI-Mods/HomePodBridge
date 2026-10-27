package dev.ryuya.homepodbridge;

import android.content.Context;

/** Numeric receiver replies only; never formats native logs or infers rejection from silence. */
record ReceiverRejection(int code, boolean terminal) {
  static final ReceiverRejection NONE = new ReceiverRejection(0, false);

  ReceiverRejection {
    if (code != 0 && (code < 400 || code > 599)) throw new IllegalArgumentException();
    if (code == 0 && terminal) throw new IllegalArgumentException();
  }

  static ReceiverRejection fromNative(int[] value) {
    if (value == null
        || value.length != 2
        || value[0] < 400
        || value[0] > 599
        || (value[1] != 0 && value[1] != 1)) return NONE;
    return new ReceiverRejection(value[0], value[1] == 1);
  }

  static ReceiverRejection load(Context context) {
    var preferences = context.getSharedPreferences("receiverResponse", Context.MODE_PRIVATE);
    return fromNative(
        new int[] {
          preferences.getInt("code", 0), preferences.getBoolean("terminal", false) ? 1 : 0
        });
  }

  void save(Context context) {
    context
        .getSharedPreferences("receiverResponse", Context.MODE_PRIVATE)
        .edit()
        .putInt("code", code)
        .putBoolean("terminal", terminal)
        .apply();
  }

  String message(Context context, boolean connected) {
    if (code == 0) return "";
    return context.getString(
        terminal
            ? R.string.receiver_request_rejected
            : connected
                ? R.string.receiver_request_compatibility
                : R.string.receiver_request_last_retry,
        code);
  }
}
