package dev.ryuya.homepodbridge;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/** In-process state only. No exported commands, credentials, PCM files, or network logs. */
final class BridgeState {
  static final String MUSIC = "com.apple.android.music";
  static final String PACKAGE = "dev.ryuya.homepodbridge";

  interface Observer {
    void changed();
  }

  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final List<Observer> OBSERVERS = new ArrayList<>();
  static List<Receiver> receivers = List.of();
  static CaptureService capture;
  static Receiver selected;
  static boolean connected;
  static int volume;
  static int status = R.string.idle;

  static void observe(Observer observer) {
    OBSERVERS.add(observer);
    observer.changed();
  }

  static void remove(Observer observer) {
    OBSERVERS.remove(observer);
  }

  static void publish() {
    for (Observer observer : new ArrayList<>(OBSERVERS)) observer.changed();
  }

  static void status(int statusId) {
    MAIN.post(
        () -> {
          status = statusId;
          publish();
        });
  }

  private BridgeState() {}
}
