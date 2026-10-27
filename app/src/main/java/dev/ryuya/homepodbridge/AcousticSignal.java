package dev.ryuya.homepodbridge;

/** Fixed quiet chirps, written only into sender PCM, never into a phone playback AudioTrack. */
final class AcousticSignal {
  static final int RATE = 44100,
      COUNT = 3,
      TONE_FRAMES = 1764,
      FIRST_FRAME = 88200,
      GAP_FRAMES = 26460;
  static final double PEAK = 0.12;
  static final int[] PHASE_CODE = {0xACE1, 0xB4D3, 0x69A7};
  static final int[] LOW = {1600, 2100, 1300}, HIGH = {3900, 4700, 3400};

  static long firstFrame(int index) {
    return FIRST_FRAME + (long) index * GAP_FRAMES;
  }

  static short sample(int index, int frame) {
    if (index < 0 || index >= COUNT || frame < 0 || frame >= TONE_FRAMES) return 0;
    return sample(LOW[index], HIGH[index], PHASE_CODE[index], frame);
  }

  static short sample(int low, int high, int code, int frame) {
    if (frame < 0 || frame >= TONE_FRAMES) return 0;
    double t = frame / (double) RATE, seconds = TONE_FRAMES / (double) RATE;
    double phase = 2 * Math.PI * (low * t + 0.5 * (high - low) * t * t / seconds);
    double window = 0.5 - 0.5 * Math.cos(2 * Math.PI * frame / (TONE_FRAMES - 1));
    int sign = ((code >> (frame * 16 / TONE_FRAMES)) & 1) == 0 ? -1 : 1;
    return (short) Math.round(32767 * PEAK * window * Math.sin(phase) * sign);
  }

  private AcousticSignal() {}
}
