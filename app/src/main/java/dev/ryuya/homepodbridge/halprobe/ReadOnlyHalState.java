package dev.ryuya.homepodbridge.halprobe;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed, read-only V3 r_submix inventory; contains no mutating transactions. */
public final class ReadOnlyHalState {
  static final String DESCRIPTOR = "android.hardware.audio.core.IModule";
  final IBinder module;

  ReadOnlyHalState() throws Exception {
    module =
        (IBinder)
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String.class)
                .invoke(null, DESCRIPTOR + "/r_submix");
    if (module == null || !DESCRIPTOR.equals(module.getInterfaceDescriptor()))
      throw new SecurityException();
    Parcel p = read(16777215);
    try {
      if (p.readInt() != 3) throw new SecurityException("Unsupported version");
    } finally {
      p.recycle();
    }
    p = read(16777214);
    try {
      if (!"eb5f1f5af402830af56b50eb66bdd60fd8cc774e".equals(p.readString()))
        throw new SecurityException("Unsupported hash");
    } finally {
      p.recycle();
    }
  }

  Parcel read(int transaction) throws Exception {
    if (transaction != 8
        && transaction != 10
        && transaction != 11
        && transaction != 16777214
        && transaction != 16777215) throw new SecurityException("Read-only method required");
    Parcel input = Parcel.obtain(), reply = Parcel.obtain();
    try {
      input.writeInterfaceToken(DESCRIPTOR);
      if (!module.transact(transaction, input, reply, 0))
        throw new IllegalStateException("Unavailable");
      reply.readException();
      return reply;
    } catch (Exception error) {
      reply.recycle();
      throw error;
    } finally {
      input.recycle();
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  Object[] objects(int transaction, String type) throws Exception {
    Parcel reply = read(transaction);
    try {
      int count = reply.readInt();
      if (count < 0 || count > 128) throw new IllegalArgumentException("Invalid inventory length");
      reply.setDataPosition(reply.dataPosition() - 4);
      Object[] result =
          reply.createTypedArray(
              (Parcelable.Creator) Class.forName(type).getField("CREATOR").get(null));
      if (reply.dataAvail() != 0) throw new IllegalArgumentException("Trailing inventory data");
      return result;
    } finally {
      reply.recycle();
    }
  }

  static Object field(Object object, String name) throws Exception {
    return object.getClass().getField(name).get(object);
  }

  static int integer(Object object, String name) throws Exception {
    return ((Number) field(object, name)).intValue();
  }

  static Object call(Object object, String name) throws Exception {
    return object.getClass().getMethod(name).invoke(object);
  }

  static boolean tag(Object object, String name) throws Exception {
    return ((Number) call(object, "getTag")).intValue()
        == object.getClass().getField(name).getInt(null);
  }

  static JSONObject format(Object object) throws Exception {
    JSONObject result = new JSONObject();
    Object rate = field(object, "sampleRate"),
        mask = field(object, "channelMask"),
        format = field(object, "format");
    result.put("rate", rate == null ? -1 : integer(rate, "value"));
    result.put(
        "stereo",
        mask != null
            && tag(mask, "layoutMask")
            && ((Number) call(mask, "getLayoutMask")).intValue() == 3);
    result.put(
        "pcm16", format != null && integer(format, "type") == 1 && integer(format, "pcm") == 1);
    return result;
  }

  JSONObject snapshot() throws Exception {
    if (!module.isBinderAlive()) throw new IllegalStateException("Module instance died");
    var ports = new JSONArray();
    var configs = new JSONArray();
    var patches = new JSONArray();
    var seenPorts = new java.util.HashSet<Integer>();
    var seenConfigs = new java.util.HashSet<Integer>();
    for (Object port : objects(11, "android.media.audio.common.AudioPort")) {
      int id = integer(port, "id");
      if (id <= 0 || !seenPorts.add(id)) throw new IllegalStateException("Duplicate port");
      var value = new JSONObject().put("id", id);
      Object ext = field(port, "ext");
      if (tag(ext, "device")) {
        Object device = field(call(ext, "getDevice"), "device"),
            type = field(device, "type"),
            address = field(device, "address");
        int kind = integer(type, "type");
        String connection = (String) field(type, "connection");
        if ((kind != 143 && kind != 11) || !"virtual".equals(connection))
          throw new SecurityException("Non-virtual device in submix module");
        value
            .put("kind", "device")
            .put("deviceType", kind)
            .put("address", tag(address, "id") ? call(address, "getId") : JSONObject.NULL);
      } else if (tag(ext, "mix")) {
        value.put("kind", "mix").put("handle", integer(call(ext, "getMix"), "handle"));
      } else throw new IllegalStateException("Unknown port kind");
      ports.put(value);
    }
    for (Object config : objects(10, "android.media.audio.common.AudioPortConfig")) {
      int id = integer(config, "id");
      if (id <= 0 || !seenConfigs.add(id)) throw new IllegalStateException("Duplicate config");
      JSONObject value = format(config).put("id", id).put("portId", integer(config, "portId"));
      Object ext = field(config, "ext");
      if (tag(ext, "mix")) value.put("handle", integer(call(ext, "getMix"), "handle"));
      configs.put(value);
    }
    Parcel reply = read(8);
    try {
      var seen = new java.util.HashSet<Integer>();
      for (var patch : FrozenHalParcel.patches(reply)) {
        if (patch.id <= 0 || !seen.add(patch.id))
          throw new IllegalStateException("Duplicate patch");
        patches.put(
            new JSONObject()
                .put("id", patch.id)
                .put("sources", new JSONArray(patch.sources))
                .put("sinks", new JSONArray(patch.sinks))
                .put("minimumFrames", patch.minimumFrames));
      }
      if (reply.dataAvail() != 0) throw new IllegalArgumentException("Trailing patches");
    } finally {
      reply.recycle();
    }
    return new JSONObject()
        .put("version", 3)
        .put("ports", ports)
        .put("configs", configs)
        .put("patches", patches);
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 0) throw new IllegalArgumentException();
    System.out.println(new ReadOnlyHalState().snapshot().toString());
  }
}
