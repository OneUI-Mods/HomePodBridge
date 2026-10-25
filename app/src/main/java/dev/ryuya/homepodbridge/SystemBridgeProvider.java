package dev.ryuya.homepodbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public final class SystemBridgeProvider extends ContentProvider {
  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Bundle call(String method, String arg, Bundle extras) {
    try {
      int systemUi =
          getContext().getPackageManager().getApplicationInfo("com.android.systemui", 0).uid;
      if (Binder.getCallingUid() != systemUi)
        throw new SecurityException("Only SystemUI can control routes");
      if ("register".equals(method) && extras != null) {
        RootConnector.register(extras.getBinder("bridge"));
        return Bundle.EMPTY;
      }
      Handler main = new Handler(Looper.getMainLooper());
      if ("select".equals(method) && extras != null) {
        String source = extras.getString("source");
        String route = extras.getString("route");
        if (source == null || source.isEmpty() || route == null || !route.startsWith("homepod-"))
          throw new IllegalArgumentException("Invalid route selection");
        main.post(() -> HomePodRoutes.selectFromSystem(source, route));
      } else if ("stop".equals(method)) {
        main.post(HomePodRoutes::stopFromSystem);
      } else throw new IllegalArgumentException("Unknown call");
      Bundle result = new Bundle();
      result.putBoolean("accepted", true);
      return result;
    } catch (android.content.pm.PackageManager.NameNotFoundException
        | android.os.RemoteException e) {
      throw new IllegalStateException("System bridge registration failed");
    }
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getType(Uri uri) {
    return null;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String selection, String[] args) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    throw new UnsupportedOperationException();
  }
}
