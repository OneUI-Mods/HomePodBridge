package dev.ryuya.homepodbridge.halprobe;

import android.content.Context;
import android.os.Looper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.json.JSONArray;
import org.json.JSONObject;

/** Root self-UID virtual output only, before any playback track exists; no PCM or network. */
final class OutputPeriodProbe {
  static JSONObject run() throws Exception {
    HalPortProbe.rootPreflight();
    pin(
        "/system/lib64/libaudiobase.so",
        "73ee495af55db5beb3852923c0b9cdf74d5a9667639b9cc87eda34e1b646786f");
    pin(
        "/system/lib64/libutils.so",
        "e1eb96ad194e2b2191c3a2a11d7f2d876fd57b30583d9fdaee1da001ba5b5e23");
    String apk = System.getenv("CLASSPATH");
    if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk"))
      throw new SecurityException("Installed helper required");
    System.load(apk + "!/lib/arm64-v8a/libhomepod_hal.so");
    ReadOnlyHalState state = new ReadOnlyHalState();
    String baseline = state.snapshot().toString();
    JSONObject result = new JSONObject().put("playbackCreated", false).put("networkStarted", false);
    if (Looper.myLooper() == null) Looper.prepare();
    Class<?> activity = Class.forName("android.app.ActivityThread");
    Object thread = activity.getMethod("systemMain").invoke(null);
    Context context = (Context) activity.getMethod("getSystemContext").invoke(thread);
    try (var policy = new RegisteredPolicy(context, new CaptureScope(false, 0, -1, true, false))) {
      JSONObject before = state.snapshot();
      int io = ownedOutput(before, policy.address);
      if (io != ownedOutput(state.snapshot(), policy.address))
        throw new SecurityException("Owned output changed");
      long[] values = probe(io);
      result
          .put("requestedFrames", 88)
          .put("platformResult", values == null ? JSONObject.NULL : new JSONArray(values));
      result.put("ownedOutputUnchanged", io == ownedOutput(state.snapshot(), policy.address));
    } catch (Exception failure) {
      result.put("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
    } finally {
      boolean restored = false;
      for (int i = 0; i < 60; i++) {
        restored = baseline.equals(state.snapshot().toString());
        if (restored) break;
        Thread.sleep(50);
      }
      result.put("resourcesRestoredExactly", restored);
    }
    return result;
  }

  static int ownedOutput(JSONObject state, String address) throws Exception {
    int port = 0, config = 0, io = 0;
    JSONArray ports = state.getJSONArray("ports"), configs = state.getJSONArray("configs");
    for (int i = 0; i < ports.length(); i++) {
      JSONObject p = ports.getJSONObject(i);
      if (p.optInt("deviceType") == 143 && address.equals(p.optString("address"))) {
        if (port != 0) throw new SecurityException("Ambiguous owned output");
        port = p.getInt("id");
      }
    }
    if (port <= 0) throw new SecurityException("Owned output missing");
    for (int i = 0; i < configs.length(); i++) {
      JSONObject c = configs.getJSONObject(i);
      if (c.getInt("portId") == port) {
        if (config != 0) throw new SecurityException("Ambiguous device config");
        config = c.getInt("id");
      }
    }
    JSONArray patches = state.getJSONArray("patches");
    for (int i = 0; i < patches.length(); i++) {
      JSONObject patch = patches.getJSONObject(i);
      JSONArray sinks = patch.getJSONArray("sinks"), sources = patch.getJSONArray("sources");
      if (sinks.length() != 1 || sinks.getInt(0) != config) continue;
      if (sources.length() != 1 || io != 0) throw new SecurityException("Ambiguous output patch");
      int source = sources.getInt(0);
      for (int j = 0; j < configs.length(); j++) {
        JSONObject c = configs.getJSONObject(j);
        if (c.getInt("id") == source
            && c.getInt("portId") == 3
            && c.getInt("rate") == 44100
            && c.getBoolean("stereo")
            && c.getBoolean("pcm16")) io = c.getInt("handle");
      }
    }
    if (io <= 0 || io > 1000000) throw new SecurityException("Owned output handle missing");
    return io;
  }

  private static void pin(String file, String expected) throws Exception {
    Path path = Path.of(file);
    if (Files.size(path) > 4194304
        || !HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
            .equals(expected)) throw new SecurityException("Unsupported platform ABI");
  }

  private static native long[] probe(int io);

  private OutputPeriodProbe() {}
}
