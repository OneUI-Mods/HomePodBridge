package dev.ryuya.homepodbridge;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

final class RootConnector {
  private static volatile IBinder bridge;

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

  static ParcelFileDescriptor open(String packageName, IBinder lifetime) throws RemoteException {
    IBinder target = bridge;
    if (target == null) throw new RemoteException("System bridge unavailable");
    Parcel input = Parcel.obtain();
    Parcel output = Parcel.obtain();
    try {
      input.writeInterfaceToken(RootProtocol.DESCRIPTOR);
      input.writeStrongBinder(lifetime);
      input.writeString(packageName);
      if (!target.transact(RootProtocol.OPEN, input, output, 0))
        throw new RemoteException("Open rejected");
      output.readException();
      return ParcelFileDescriptor.CREATOR.createFromParcel(output);
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
