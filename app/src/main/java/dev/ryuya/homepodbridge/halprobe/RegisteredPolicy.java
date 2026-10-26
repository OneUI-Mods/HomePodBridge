package dev.ryuya.homepodbridge.halprobe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.HandlerThread;
import android.os.Looper;

/** Owns one playback policy and its exact virtual output registration. */
final class RegisteredPolicy implements AutoCloseable {
  private final AudioManager manager;
  private final Class<?> policyType;
  private final HandlerThread callbacks = new HandlerThread("HomePod-HAL-policy");
  private Object policy;
  private PolicyRouteLease route;
  final String address;

  RegisteredPolicy(Context context, CaptureScope scope) throws Exception {
    manager = context.getSystemService(AudioManager.class);
    policyType = Class.forName("android.media.audiopolicy.AudioPolicy");
    callbacks.start();
    try {
      Class<?> ruleType = Class.forName("android.media.audiopolicy.AudioMixingRule");
      Object rules = Class.forName(ruleType.getName() + "$Builder").getConstructor().newInstance();
      if (scope.whole()) {
        rules.getClass().getMethod("setTargetMixRole", int.class).invoke(rules, 0);
        rules
            .getClass()
            .getMethod("excludeMixRule", int.class, Object.class)
            .invoke(rules, 4, Integer.valueOf(scope.excludedUid()));
        for (int usage : scope.excludedUsages())
          rules
              .getClass()
              .getMethod("excludeRule", AudioAttributes.class, int.class)
              .invoke(rules, new AudioAttributes.Builder().setUsage(usage).build(), 1);
      } else {
        rules
            .getClass()
            .getMethod("addRule", AudioAttributes.class, int.class)
            .invoke(rules, new AudioAttributes.Builder().setUsage(scope.matchedUsage()).build(), 1);
        rules
            .getClass()
            .getMethod("addMixRule", int.class, Object.class)
            .invoke(rules, 4, Integer.valueOf(scope.selectedUid()));
      }
      Class<?> mixType = Class.forName("android.media.audiopolicy.AudioMix");
      Object mix =
          Class.forName(mixType.getName() + "$Builder")
              .getConstructor(ruleType)
              .newInstance(rules.getClass().getMethod("build").invoke(rules));
      mix.getClass().getMethod("setRouteFlags", int.class).invoke(mix, 2);
      mix.getClass()
          .getMethod("setFormat", AudioFormat.class)
          .invoke(
              mix,
              new AudioFormat.Builder()
                  .setSampleRate(44100)
                  .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                  .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                  .build());
      Object audioMix = mix.getClass().getMethod("build").invoke(mix);
      Object builder =
          Class.forName(policyType.getName() + "$Builder")
              .getConstructor(Context.class)
              .newInstance(context);
      builder
          .getClass()
          .getMethod("setLooper", Looper.class)
          .invoke(builder, callbacks.getLooper());
      builder.getClass().getMethod("addMix", mixType).invoke(builder, audioMix);
      policy = builder.getClass().getMethod("build").invoke(builder);
      int status =
          (Integer)
              AudioManager.class
                  .getMethod("registerAudioPolicy", policyType)
                  .invoke(manager, policy);
      if (status != 0) throw new IllegalStateException("Playback policy refused");
      address = (String) mixType.getMethod("getRegistration").invoke(audioMix);
      route = PolicyRouteLease.open(context, address);
    } catch (Exception failure) {
      close();
      throw failure;
    }
  }

  @Override
  public void close() throws Exception {
    try {
      if (route != null) route.close();
    } finally {
      try {
        if (policy != null)
          AudioManager.class.getMethod("unregisterAudioPolicy", policyType).invoke(manager, policy);
      } finally {
        policy = null;
        callbacks.quitSafely();
        callbacks.join(1000);
      }
    }
  }
}
