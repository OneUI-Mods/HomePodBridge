package dev.ryuya.homepodbridge;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

final class RootConnector {
  private static volatile IBinder bridge;
  private static final java.util.concurrent.ExecutorService controls =
      java.util.concurrent.Executors.newSingleThreadExecutor();

  static void volumeEndpoint(IBinder lifetime, IBinder callback) {
    IBinder target = bridge;
    if (target == null) return;
    controls.execute(
        () -> {
          Parcel input = Parcel.obtain();
          Parcel output = Parcel.obtain();
          try {
            input.writeInterfaceToken(RootProtocol.DESCRIPTOR);
            input.writeStrongBinder(lifetime);
            input.writeStrongBinder(callback);
            if (target.transact(RootProtocol.VOLUME_ENDPOINT, input, output, 0))
              output.readException();
          } catch (RemoteException | RuntimeException ignored) {
            // The ordinary remote session remains usable if a ROM lacks this API.
          } finally {
            input.recycle();
            output.recycle();
          }
        });
  }

  static void register(IBinder value) throws RemoteException {
    if (value == null) throw new IllegalArgumentException("Missing system bridge");
    bridge = value;
    value.linkToDeath(
        () -> {
          if (bridge == value) bridge = null;
        },
        0);
    new android.os.Handler(android.os.Looper.getMainLooper()).post(BridgeState::publish);
  }

  static void request(android.content.Context context) {
    if (!available())
      context.sendBroadcast(
          new android.content.Intent(RootProtocol.REQUEST).setPackage("com.android.systemui"));
  }

  static boolean available() {
    IBinder value = bridge;
    return value != null && value.isBinderAlive();
  }

  record Input(ParcelFileDescriptor pipe, boolean timed) {}

  static long[] captureTiming(IBinder lifetime) {
    IBinder target = bridge;
    if (target == null) return null;
    Parcel input = Parcel.obtain();
    Parcel output = Parcel.obtain();
    try {
      input.writeInterfaceToken(RootProtocol.DESCRIPTOR);
      input.writeStrongBinder(lifetime);
      if (!target.transact(RootProtocol.CAPTURE_TIMING, input, output, 0)) return null;
      output.readException();
      return output.createLongArray();
    } catch (RemoteException | RuntimeException ignored) {
      return null;
    } finally {
      input.recycle();
      output.recycle();
    }
  }

  static Input open(String packageName, IBinder lifetime, StreamSettings settings)
      throws RemoteException {
    IBinder target = bridge;
    if (target == null) throw new RemoteException("System bridge unavailable");
    Parcel input = Parcel.obtain();
    Parcel output = Parcel.obtain();
    try {
      input.writeInterfaceToken(RootProtocol.DESCRIPTOR);
      input.writeStrongBinder(lifetime);
      input.writeString(packageName);
      input.writeBoolean(settings.wholeDevice());
      input.writeInt(settings.effectiveBlockMs());
      input.writeBoolean(settings.lowLatency());
      boolean timed = target.transact(RootProtocol.OPEN_TIMED, input, output, 0);
      if (!timed) {
        output.setDataSize(0);
        output.setDataPosition(0);
        if (!target.transact(RootProtocol.OPEN_CONFIGURED, input, output, 0))
          throw new RemoteException("Reload SystemUI to load the updated audio bridge");
      }
      output.readException();
      return new Input(ParcelFileDescriptor.CREATOR.createFromParcel(output), timed);
    } finally {
      input.recycle();
      output.recycle();
    }
  }

  static void close(IBinder lifetime) {
    IBinder target = bridge;
    if (target == null) return;
    Parcel input = Parcel.obtain();
    Parcel output = Parcel.obtain();
    try {
      input.writeInterfaceToken(RootProtocol.DESCRIPTOR);
      input.writeStrongBinder(lifetime);
      if (target.transact(RootProtocol.CLOSE, input, output, 0)) output.readException();
    } catch (RemoteException | RuntimeException ignored) {
    } finally {
      input.recycle();
      output.recycle();
    }
  }

  private RootConnector() {}
}
