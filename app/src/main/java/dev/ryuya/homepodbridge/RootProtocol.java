package dev.ryuya.homepodbridge;

final class RootProtocol {
  static final String PACKAGE = "dev.ryuya.homepodbridge";
  static final String AUTHORITY = PACKAGE + ".system";
  static final String DESCRIPTOR = PACKAGE + ".SystemAudio";
  static final String REQUEST = PACKAGE + ".REQUEST_SYSTEM_BRIDGE";
  static final int OPEN = android.os.IBinder.FIRST_CALL_TRANSACTION;
  static final int CLOSE = OPEN + 1;
  static final int OPEN_CONFIGURED = CLOSE + 1;

  private RootProtocol() {}
}
