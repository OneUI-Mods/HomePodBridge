package dev.ryuya.homepodbridge.halprobe;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import java.util.ArrayList;
import java.util.List;

/** Small, bounded readers for the verified V3 module. No HAL transactions or setters. */
final class FrozenHalParcel {
  static final class Patch {
    int id, minimumFrames;
    int[] sources, sinks, latencies;
  }

  static final class Grant {
    final int fd, offset;
    final long extent;

    Grant(int fd, int offset, long extent) {
      this.fd = fd;
      this.offset = offset;
      this.extent = extent;
    }
  }

  static final class Queue {
    Grant[] grants;
    ParcelFileDescriptor[] fds;
    int quantum, flags;
  }

  static final class Stream implements AutoCloseable {
    IBinder binder;
    Queue command, reply, data;
    int frameBytes;
    long bufferFrames;
    final List<ParcelFileDescriptor> acquired = new ArrayList<>();

    public void close() {
      for (var fd : acquired)
        try {
          fd.close();
        } catch (Exception ignored) {
        }
      acquired.clear();
    }
  }

  static final class Span implements AutoCloseable {
    final Parcel parcel;
    final int end;

    Span(Parcel p, int parent) {
      parcel = p;
      int begin = p.dataPosition();
      if (begin < 0 || begin > parent - 4 || parent > p.dataSize()) throw bad();
      int size = p.readInt();
      if (size < 4 || size > 65536 || size > parent - begin) throw bad();
      end = begin + size;
    }

    void require(int size) {
      if (size < 0 || parcel.dataPosition() > end - size) throw bad();
    }

    int integer() {
      require(4);
      return parcel.readInt();
    }

    long number() {
      require(8);
      return parcel.readLong();
    }

    int[] integers(int max) {
      int count = integer();
      if (count < 0 || count > max) throw bad();
      require(count * 4);
      int[] result = new int[count];
      for (int i = 0; i < count; i++) result[i] = parcel.readInt();
      return result;
    }

    Span typed() {
      if (integer() != 1) throw bad();
      return new Span(parcel, end);
    }

    public void close() {
      parcel.setDataPosition(end);
    }
  }

  static IllegalArgumentException bad() {
    return new IllegalArgumentException("Invalid frozen parcel");
  }

  static Span typed(Parcel p) {
    if (p.dataAvail() < 4 || p.readInt() != 1) throw bad();
    return new Span(p, p.dataSize());
  }

  static Patch patch(Parcel p) {
    try (Span b = typed(p)) {
      Patch value = new Patch();
      value.id = b.integer();
      value.sources = b.integers(16);
      value.sinks = b.integers(16);
      value.minimumFrames = b.integer();
      value.latencies = b.integers(16);
      return value;
    }
  }

  static List<Patch> patches(Parcel p) {
    if (p.dataAvail() < 4) throw bad();
    int count = p.readInt();
    if (count < 0 || count > 128) throw bad();
    var values = new ArrayList<Patch>();
    for (int i = 0; i < count; i++) values.add(patch(p));
    return values;
  }

  static void writePatch(Parcel p, int id, int[] sources, int[] sinks) {
    if (id != 0
        || sources.length < 1
        || sources.length > 16
        || sinks.length < 1
        || sinks.length > 16) throw bad();
    p.writeInt(1);
    int begin = p.dataPosition();
    p.writeInt(0);
    p.writeInt(id);
    p.writeIntArray(sources);
    p.writeIntArray(sinks);
    p.writeInt(0);
    p.writeIntArray(new int[0]);
    int end = p.dataPosition();
    p.setDataPosition(begin);
    p.writeInt(end - begin);
    p.setDataPosition(end);
  }

  static int begin(Parcel p) {
    int start = p.dataPosition();
    p.writeInt(0);
    return start;
  }

  static void end(Parcel p, int start) {
    int stop = p.dataPosition();
    p.setDataPosition(start);
    p.writeInt(stop - start);
    p.setDataPosition(stop);
  }

  static void writeOpenArgs(Parcel p, int config, long frames, boolean input) throws Exception {
    if (config <= 0 || frames < 1 || frames > 262144) throw bad();
    Object mask =
        Class.forName("android.media.audio.common.AudioChannelLayout")
            .getMethod("layoutMask", int.class)
            .invoke(null, 3);
    p.writeInt(1);
    int args = begin(p);
    p.writeInt(config);
    p.writeInt(1);
    int metadata = begin(p);
    p.writeInt(1); // One track, never a microphone source.
    p.writeInt(1);
    int track = begin(p);
    if (input) {
      p.writeInt(8);
      p.writeFloat(1);
      p.writeInt(0);
    } // REMOTE_SUBMIX, null destination.
    else {
      p.writeInt(1);
      p.writeInt(2);
      p.writeFloat(1);
    } // MEDIA/MUSIC.
    p.writeTypedObject((android.os.Parcelable) mask, 0);
    if (!input) p.writeInt(0); // Null source device.
    p.writeStringArray(new String[0]);
    end(p, track);
    end(p, metadata);
    if (!input) p.writeInt(0); // No offload.
    p.writeLong(frames);
    if (!input) {
      p.writeStrongBinder(null);
      p.writeStrongBinder(null);
    }
    end(p, args);
  }

  static Stream stream(Parcel p) {
    return stream(p, binder -> {});
  }

  static Stream stream(Parcel p, java.util.function.Consumer<IBinder> opened) {
    Stream result = new Stream();
    try (Span b = typed(p)) {
      b.require(4);
      result.binder = p.readStrongBinder();
      b.require(0);
      if (result.binder == null) throw bad();
      opened.accept(result.binder); // Supervisor retains the endpoint before decoding FD payloads.
      try (Span desc = b.typed()) {
        result.command = queue(desc, result, 8);
        result.reply = queue(desc, result, 56);
        result.frameBytes = desc.integer();
        result.bufferFrames = desc.number();
        if (result.frameBytes != 4 || result.bufferFrames < 1 || result.bufferFrames > 262144)
          throw bad();
        if (desc.integer() != 1 || desc.integer() != 0)
          throw bad(); // Required FMQ union, never mmap.
        result.data = queue(desc, result, 1);
        if (result.data.grants[2].extent != result.frameBytes * result.bufferFrames) throw bad();
      }
      return result;
    } catch (Throwable failure) {
      result.close();
      throw failure;
    }
  }

  private static Queue queue(Span parent, Stream owner, int quantum) {
    Parcel p = parent.parcel;
    Queue q = new Queue();
    try (Span b = parent.typed()) {
      int count = b.integer();
      if (count != 3 && count != 4) throw bad();
      q.grants = new Grant[count];
      for (int i = 0; i < count; i++)
        try (Span grant = b.typed()) {
          int fd = grant.integer(), offset = grant.integer();
          long extent = grant.number();
          if (fd < 0 || fd >= 8 || offset < 0 || extent < 1 || extent > 4 * 1024 * 1024)
            throw bad();
          q.grants[i] = new Grant(fd, offset, extent);
        }
      try (Span h = b.typed()) {
        int fds = h.integer();
        if (fds < 1 || fds > 8) throw bad();
        q.fds = new ParcelFileDescriptor[fds];
        for (int i = 0; i < fds; i++) {
          h.require(4);
          ParcelFileDescriptor fd = p.readTypedObject(ParcelFileDescriptor.CREATOR);
          if (fd != null) owner.acquired.add(fd);
          h.require(0);
          if (fd == null) throw bad();
          q.fds[i] = fd;
        }
        if (h.integers(16).length != 0) throw bad();
      }
      q.quantum = b.integer();
      q.flags = b.integer();
      if (q.quantum != quantum || q.flags != 1) throw bad();
      for (int i = 0; i < count; i++) {
        Grant g = q.grants[i];
        if (g.fd >= q.fds.length) throw bad();
        if (i < 2 && (g.extent != 8 || g.offset % 8 != 0)) throw bad();
        if (i == 3 && (g.extent != 4 || g.offset % 4 != 0)) throw bad();
      }
      if (q.grants[2].extent % quantum != 0) throw bad();
      return q;
    }
  }
}
