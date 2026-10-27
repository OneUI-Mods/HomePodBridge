package dev.ryuya.homepodbridge;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/** In-process state only. No exported commands, credentials, PCM files, or network logs. */
final class BridgeState {
  enum CaptureBackend {
    STANDARD,
    CHECKING,
    DEDICATED,
    DEDICATED_SHORT,
    DEDICATED_TEST,
    FALLBACK
  }

  static CaptureBackend captureBackend = CaptureBackend.STANDARD;
  static final String MUSIC = "com.apple.android.music";
  static final String PACKAGE = "dev.ryuya.homepodbridge";

  interface Observer {
    void changed();

    default void timingChanged() {}
  }

  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final List<Observer> OBSERVERS = new ArrayList<>();
  static List<Receiver> receivers = List.of();
  static final java.util.Set<String> castCategories = new java.util.LinkedHashSet<>();
  static CaptureService capture;
  static Receiver selected;
  static boolean connected;
  static int volume;
  static int[] timing;
  static PipelineTiming pipeline;
  static NetworkTiming networkTiming;
  static ConnectionTiming connectionTiming;
  static dev.ryuya.homepodbridge.halprobe.PhoneTiming activePhoneTiming;
  static volatile String captureRegistration;
  static volatile OwnedAudioPath.Update phonePath;
  static int status = R.string.idle;
  static ReceiverRejection receiverRejection = ReceiverRejection.NONE;

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

  static void publishTiming() {
    for (Observer observer : new ArrayList<>(OBSERVERS)) observer.timingChanged();
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
