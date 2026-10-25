package dev.ryuya.homepodbridge;

final class RootProtocol {
  static final String PACKAGE = "dev.ryuya.homepodbridge";
  static final String AUTHORITY = PACKAGE + ".system";
  static final String DESCRIPTOR = PACKAGE + ".SystemAudio";
  static final String REQUEST = PACKAGE + ".REQUEST_SYSTEM_BRIDGE";
  static final int OPEN = android.os.IBinder.FIRST_CALL_TRANSACTION;
  static final int CLOSE = OPEN + 1;
  static final int OPEN_CONFIGURED = CLOSE + 1;
  static final int VOLUME_ENDPOINT = OPEN_CONFIGURED + 1;
  static final int OPEN_TIMED = VOLUME_ENDPOINT + 1;
  static final int CAPTURE_TIMING = OPEN_TIMED + 1;
  static final int OPEN_SILENT_PROBE = CAPTURE_TIMING + 1;
  static final String VOLUME_CALLBACK = PACKAGE + ".VolumeCallback";
  static final int ADJUST_VOLUME = android.os.IBinder.FIRST_CALL_TRANSACTION;
  static final int SET_VOLUME = ADJUST_VOLUME + 1;

  private RootProtocol() {}
}
