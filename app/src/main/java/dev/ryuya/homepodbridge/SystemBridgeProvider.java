package dev.ryuya.homepodbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

public final class SystemBridgeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        try {
            int systemUi = getContext().getPackageManager().getApplicationInfo("com.android.systemui", 0).uid;
            if (Binder.getCallingUid() != systemUi) throw new SecurityException("Only SystemUI can register");
            if (!"register".equals(method) || extras == null) throw new IllegalArgumentException("Unknown call");
            RootConnector.register(extras.getBinder("bridge"));
            return Bundle.EMPTY;
        } catch (android.content.pm.PackageManager.NameNotFoundException | android.os.RemoteException e) {
            throw new IllegalStateException("System bridge registration failed");
        }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
