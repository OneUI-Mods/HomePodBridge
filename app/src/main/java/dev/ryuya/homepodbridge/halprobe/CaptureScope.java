package dev.ryuya.homepodbridge.halprobe;

/** Criteria consumed directly by the policy builder; microphone input is never an option. */
public record CaptureScope(
    boolean whole, int selectedUid, int excludedUid, boolean zeroOnly, boolean pulse) {
  public static CaptureScope parse(String mode, int selectedUid, int bridgeUid) {
    if (bridgeUid <= 0 || selectedUid < 0) throw new IllegalArgumentException();
    return switch (mode) {
      case "selected" -> new CaptureScope(false, selectedUid, bridgeUid, false, false);
      case "whole" -> new CaptureScope(true, -1, bridgeUid, false, false);
      case "test-target-752", "test-target-768", "test-selected" ->
          new CaptureScope(false, 0, bridgeUid, true, false);
      case "test-whole" -> new CaptureScope(true, -1, bridgeUid, true, false);
      case "test-cycle" -> new CaptureScope(false, 0, bridgeUid, true, true);
      default -> throw new IllegalArgumentException("Unknown capture scope");
    };
  }

  public int matchedUsage() {
    return whole ? -1 : 1;
  }

  public int[] excludedUsages() {
    return whole ? new int[] {2, 3} : new int[0];
  }
}
