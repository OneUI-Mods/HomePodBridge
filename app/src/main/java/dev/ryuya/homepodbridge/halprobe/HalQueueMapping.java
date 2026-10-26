package dev.ryuya.homepodbridge.halprobe;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native inspection runs in a child; the Java owner retains all original stream handles/FDs. */
public final class HalQueueMapping {
  private static native long[] inspect(int[] fds, long[] grants, int quantum, int flags);

  static JSONObject inspectInChild(List<FrozenHalParcel.Stream> streams, String nonce)
      throws Exception {
    String apk = System.getenv("CLASSPATH");
    if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk"))
      throw new SecurityException();
    String name = "hpb-map-" + nonce;
    Process child = null;
    try (var server = new LocalServerSocket(name)) {
      child =
          new ProcessBuilder(
                  "app_process",
                  "/system/bin",
                  HalQueueMapping.class.getName(),
                  name,
                  nonce,
                  Integer.toString(android.os.Process.myPid()))
              .start();
      var pending = new android.system.StructPollfd();
      pending.fd = server.getFileDescriptor();
      pending.events = (short) android.system.OsConstants.POLLIN;
      long connectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
      while (System.nanoTime() < connectDeadline && child.isAlive()) {
        android.system.Os.poll(new android.system.StructPollfd[] {pending}, 100);
        if ((pending.revents & android.system.OsConstants.POLLIN) != 0) break;
      }
      if ((pending.revents & android.system.OsConstants.POLLIN) == 0) {
        String reason =
            child.isAlive()
                ? "connection timeout"
                : new String(
                    child.getErrorStream().readNBytes(1024),
                    java.nio.charset.StandardCharsets.UTF_8);
        throw new java.io.IOException("Mapping worker did not connect: " + reason);
      }
      try (var socket = server.accept()) {
        socket.setSoTimeout(3000);
        var peer = socket.getPeerCredentials();
        String peerStat =
            new String(
                java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of("/proc", Integer.toString(peer.getPid()), "stat")),
                java.nio.charset.StandardCharsets.UTF_8);
        if (peer.getUid() != 0
            || !peerStat
                .substring(peerStat.lastIndexOf(')') + 2)
                .split("\\s+")[1]
                .equals(Integer.toString(android.os.Process.myPid())))
          throw new SecurityException("Unexpected mapping worker");
        var descriptors = new ArrayList<FileDescriptor>();
        var bytes = new ByteArrayOutputStream();
        var metadata = new DataOutputStream(bytes);
        metadata.writeUTF(nonce);
        metadata.writeInt(streams.size() * 3);
        for (var stream : streams)
          for (var queue : List.of(stream.command, stream.reply, stream.data)) {
            metadata.writeInt(queue.quantum);
            metadata.writeInt(queue.flags);
            metadata.writeInt(queue.fds.length);
            metadata.writeInt(queue.grants.length);
            for (var fd : queue.fds) descriptors.add(fd.getFileDescriptor());
            for (var grant : queue.grants) {
              metadata.writeInt(grant.fd);
              metadata.writeLong(grant.offset);
              metadata.writeLong(grant.extent);
            }
          }
        if (descriptors.size() > 48 || bytes.size() > 8192) throw new SecurityException();
        socket.setFileDescriptorsForSend(descriptors.toArray(new FileDescriptor[0]));
        var output = new DataOutputStream(socket.getOutputStream());
        output.writeByte(0x51);
        output.flush();
        socket.setFileDescriptorsForSend(null);
        output.writeInt(bytes.size());
        output.write(bytes.toByteArray());
        output.flush();
        var result = new DataInputStream(socket.getInputStream()).readUTF();
        if (result.length() > 2048) throw new SecurityException();
        if (!child.waitFor(2, TimeUnit.SECONDS) || child.exitValue() != 0)
          throw new java.io.IOException("Mapping worker failed");
        return new JSONObject(result);
      }
    } finally {
      if (child != null) child.destroyForcibly();
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3
        || android.os.Process.myUid() != 0
        || !args[0].equals("hpb-map-" + args[1])
        || !java.util.UUID.fromString(args[1]).toString().equals(args[1])
        || !args[2].matches("[1-9][0-9]{0,6}")) throw new SecurityException();
    String apk = System.getenv("CLASSPATH");
    if (apk == null || !apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk"))
      throw new SecurityException();
    System.load(apk + "!/lib/arm64-v8a/libc++_shared.so");
    System.load(apk + "!/lib/arm64-v8a/libhomepod_fmq_probe.so");
    FileDescriptor[] incoming = null;
    var owned = new ArrayList<ParcelFileDescriptor>();
    try (var socket = new LocalSocket()) {
      socket.connect(new LocalSocketAddress(args[0], LocalSocketAddress.Namespace.ABSTRACT));
      socket.setSoTimeout(3000);
      var peer = socket.getPeerCredentials();
      if (peer.getUid() != 0 || peer.getPid() != Integer.parseInt(args[2]))
        throw new SecurityException();
      var input = new DataInputStream(socket.getInputStream());
      if (input.readUnsignedByte() != 0x51) throw new SecurityException();
      incoming = socket.getAncillaryFileDescriptors();
      if (incoming == null || incoming.length == 0 || incoming.length > 48)
        throw new SecurityException();
      int length = input.readInt();
      if (length <= 0 || length > 8192) throw new SecurityException();
      byte[] data = new byte[length];
      input.readFully(data);
      var metadata = new DataInputStream(new ByteArrayInputStream(data));
      if (!metadata.readUTF().equals(args[1]) || metadata.readInt() != 6)
        throw new SecurityException();
      JSONArray reports = new JSONArray();
      int next = 0;
      for (int i = 0; i < 6; i++) {
        int quantum = metadata.readInt(),
            flags = metadata.readInt(),
            fdCount = metadata.readInt(),
            grantCount = metadata.readInt();
        if (quantum != new int[] {8, 56, 1}[i % 3]
            || flags != 1
            || fdCount < 1
            || fdCount > 8
            || next + fdCount > incoming.length
            || (grantCount != 3 && grantCount != 4)) throw new SecurityException();
        int[] fds = new int[fdCount];
        for (int f = 0; f < fdCount; f++) {
          var copy = ParcelFileDescriptor.dup(incoming[next++]);
          owned.add(copy);
          fds[f] = copy.getFd();
        }
        long[] grants = new long[grantCount * 3];
        for (int g = 0; g < grantCount; g++) {
          grants[g * 3] = metadata.readInt();
          grants[g * 3 + 1] = metadata.readLong();
          grants[g * 3 + 2] = metadata.readLong();
        }
        long[] result = inspect(fds, grants, quantum, flags);
        if (result == null || result.length != 3)
          throw new SecurityException("Queue mapping rejected");
        reports.put(
            new JSONObject()
                .put("quantum", quantum)
                .put("capacityBytes", result[0])
                .put("readBytes", result[1])
                .put("writtenBytes", result[2]));
      }
      if (next != incoming.length || metadata.available() != 0) throw new SecurityException();
      new DataOutputStream(socket.getOutputStream())
          .writeUTF(
              new JSONObject()
                  .put("mappedQueues", 6)
                  .put("queues", reports)
                  .put("started", false)
                  .toString());
    } finally {
      for (var fd : owned)
        try {
          fd.close();
        } catch (Exception ignored) {
        }
      if (incoming != null)
        for (var fd : incoming)
          try {
            android.system.Os.close(fd);
          } catch (Exception ignored) {
          }
    }
  }

  private HalQueueMapping() {}
}
