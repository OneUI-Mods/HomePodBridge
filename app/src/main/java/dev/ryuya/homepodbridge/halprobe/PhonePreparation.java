package dev.ryuya.homepodbridge.halprobe;

/** Closed two-operation plan; native ownership/exclusion checks still run for each field. */
public record PhonePreparation(PhoneTiming timing) {
  public static PhonePreparation parse(String value) {
    var match =
        java.util.regex.Pattern.compile("prepare-phone-([0-9]{1,2})-([0-9]{2,3})").matcher(value);
    if (!match.matches()) return null;
    try {
      return new PhonePreparation(
          new PhoneTiming(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2))));
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  public String command() {
    return "prepare-phone-" + timing.periodMs() + "-" + timing.reserveFrames();
  }

  public int reserveOperation() {
    return 10000 + timing.reserveFrames();
  }

  public int windowOperation() {
    return 30000 + timing.latencyWindowFrames();
  }

  public int rollbackOperation() {
    return 20000 + timing.reserveFrames();
  }
}
