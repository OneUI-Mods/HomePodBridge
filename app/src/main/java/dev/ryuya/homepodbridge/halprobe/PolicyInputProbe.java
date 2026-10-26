package dev.ryuya.homepodbridge.halprobe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.HandlerThread;
import android.os.Looper;
import org.json.JSONObject;

/** Root self-UID policy only; optional source contains fixed zeros. No AudioRecord construction. */
final class PolicyInputProbe {
  static JSONObject run(
      boolean routeOnly,
      java.util.UUID nonce,
      boolean directCapture,
      int target,
      boolean forwardZero)
      throws Exception {
    var state = new ReadOnlyHalState();
    String baseline = state.snapshot().toString();
    if (Looper.myLooper() == null) Looper.prepare();
    Class<?> activity = Class.forName("android.app.ActivityThread");
    Object thread = activity.getMethod("systemMain").invoke(null);
    Context context = (Context) activity.getMethod("getSystemContext").invoke(thread);
    AudioManager manager = context.getSystemService(AudioManager.class);
    Class<?> ruleType = Class.forName("android.media.audiopolicy.AudioMixingRule");
    Object rules = Class.forName(ruleType.getName() + "$Builder").getConstructor().newInstance();
    rules
        .getClass()
        .getMethod("addRule", AudioAttributes.class, int.class)
        .invoke(
            rules, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(), 1);
    rules
        .getClass()
        .getMethod("addMixRule", int.class, Object.class)
        .invoke(rules, 4, Integer.valueOf(android.os.Process.myUid()));
    Object rule = rules.getClass().getMethod("build").invoke(rules);
    Class<?> mixType = Class.forName("android.media.audiopolicy.AudioMix");
    Object mixBuilder =
        Class.forName(mixType.getName() + "$Builder").getConstructor(ruleType).newInstance(rule);
    mixBuilder.getClass().getMethod("setRouteFlags", int.class).invoke(mixBuilder, 2);
    mixBuilder
        .getClass()
        .getMethod("setFormat", AudioFormat.class)
        .invoke(
            mixBuilder,
            new AudioFormat.Builder()
                .setSampleRate(44100)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build());
    Object mix = mixBuilder.getClass().getMethod("build").invoke(mixBuilder);
    Class<?> policyType = Class.forName("android.media.audiopolicy.AudioPolicy");
    Object builder =
        Class.forName(policyType.getName() + "$Builder")
            .getConstructor(Context.class)
            .newInstance(context);
    HandlerThread callbacks = new HandlerThread("HomePod-policy-input-probe");
    callbacks.start();
    Object policy = null;
    PolicyInputLease lease = null;
    PolicyRouteLease route = null;
    boolean registered = false;
    JSONObject result = new JSONObject();
    Exception failure = null;
    String phase = "buildPolicy";
    try {
      builder
          .getClass()
          .getMethod("setLooper", Looper.class)
          .invoke(builder, callbacks.getLooper());
      builder.getClass().getMethod("addMix", mixType).invoke(builder, mix);
      policy = builder.getClass().getMethod("build").invoke(builder);
      phase = "registerPolicy";
      int status =
          (Integer)
              AudioManager.class
                  .getMethod("registerAudioPolicy", policyType)
                  .invoke(manager, policy);
      if (status != 0) throw new IllegalStateException("Self-UID policy registration refused");
      registered = true;
      String address = (String) mixType.getMethod("getRegistration").invoke(mix);
      phase = "openInput";
      if (routeOnly) route = PolicyRouteLease.open(context, address);
      else lease = PolicyInputLease.open(context, address);
      result
          .put("inputStarted", !routeOnly)
          .put("virtualOutputConnected", routeOnly)
          .put("recordCreated", false)
          .put("playbackCreated", directCapture)
          .put("zeroSourceOnly", directCapture)
          .put("input", lease == null ? 0 : lease.input);
      if (directCapture)
        result.put(
            "directCapture", HalPortProbe.policyCapture(address, nonce, target, forwardZero));
    } catch (Exception error) {
      failure = error;
    } finally {
      try {
        if (lease != null) lease.close();
        if (route != null) route.close();
      } catch (Exception error) {
        failure = error;
      }
      try {
        if (registered)
          AudioManager.class.getMethod("unregisterAudioPolicy", policyType).invoke(manager, policy);
      } catch (Exception error) {
        failure = error;
      }
      callbacks.quitSafely();
      callbacks.join(1000);
    }
    boolean restored = false;
    for (int i = 0; i < 20; i++) {
      restored = baseline.equals(state.snapshot().toString());
      if (restored) break;
      Thread.sleep(50);
    }
    result.put("resourcesRestoredExactly", restored);
    if (failure != null) {
      Throwable cause = failure;
      while (cause.getCause() != null) cause = cause.getCause();
      result
          .put("phase", phase)
          .put("failure", cause.getClass().getSimpleName() + ": " + cause.getMessage());
      var frames = new org.json.JSONArray();
      for (StackTraceElement frame : cause.getStackTrace()) {
        if (frames.length() >= 8) break;
        frames.put(frame.toString());
      }
      result.put("failureFrames", frames);
    }
    if (!restored) result.put("baseline", new JSONObject(baseline)).put("after", state.snapshot());
    return result;
  }

  private PolicyInputProbe() {}
}
