package dev.ryuya.homepodbridge;

import android.content.Context;

/** Independent of the user's audited capture, codec and receiver timing settings. */
record NetworkSettings(boolean priority) {
  static NetworkSettings load(Context context) {
    return new NetworkSettings(
        context
            .getSharedPreferences("network", Context.MODE_PRIVATE)
            .getBoolean("priority", false));
  }

  void save(Context context) {
    context
        .getSharedPreferences("network", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("priority", priority)
        .apply();
  }
}
