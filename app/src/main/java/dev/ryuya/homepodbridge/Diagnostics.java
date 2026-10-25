package dev.ryuya.homepodbridge;

import android.content.Context;
import java.nio.charset.StandardCharsets;

final class Diagnostics {
  static synchronized void route(Context context, String event) {
    try {
      java.io.File file = new java.io.File(context.getFilesDir(), "route-events.txt");
      int mode = file.length() < 16384 ? Context.MODE_APPEND : Context.MODE_PRIVATE;
      try (var output = context.openFileOutput("route-events.txt", mode)) {
        output.write(
            (android.os.SystemClock.elapsedRealtime() + " " + event + "\n")
                .getBytes(StandardCharsets.UTF_8));
      }
    } catch (java.io.IOException ignored) {
    }
  }

  private Diagnostics() {}
}
