package dev.ryuya.homepodbridge;

/** Creates only the official CLI's missing SystemUI private directory through Android's Context. */
public final class GalaxyReloadPrepare {
  public static void main(String[] args) {
    try {
      if (args.length != 0 || android.os.Process.myUid() != 0) throw new SecurityException();
      if (android.os.Looper.myLooper() == null) android.os.Looper.prepare();
      Class<?> activity = Class.forName("android.app.ActivityThread");
      Object thread = activity.getMethod("systemMain").invoke(null);
      android.content.Context context =
          (android.content.Context) activity.getMethod("getSystemContext").invoke(thread);
      var app = context.getPackageManager().getApplicationInfo("com.android.systemui", 0);
      if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
          || app.uid < 1000
          || app.uid >= 100000) throw new SecurityException();
      java.io.File expected = new java.io.File("/data/user_de/0/com.android.systemui/files");
      java.io.File parent = expected.getParentFile();
      if (!parent.getPath().equals(app.deviceProtectedDataDir)
          || java.nio.file.Files.isSymbolicLink(parent.toPath())
          || java.nio.file.Files.isSymbolicLink(expected.toPath())) throw new SecurityException();
      if (!parent.exists()) {
        java.nio.file.Files.createDirectory(parent.toPath());
        android.system.Os.chown(parent.getPath(), app.uid, app.uid);
        android.system.Os.chmod(parent.getPath(), 0700);
      }
      if (!parent.isDirectory()
          || ((Number) java.nio.file.Files.getAttribute(parent.toPath(), "unix:uid")).intValue()
              != app.uid) throw new SecurityException();
      boolean existed = expected.exists();
      var target =
          context
              .createPackageContext("com.android.systemui", 0)
              .createDeviceProtectedStorageContext()
              .getFilesDir();
      if (!target.getCanonicalPath().equals(expected.getPath()) || !target.isDirectory())
        throw new SecurityException();
      if (!existed) {
        android.system.Os.chown(target.getPath(), app.uid, app.uid);
        android.system.Os.chmod(target.getPath(), 0700);
      }
      if (((Number) java.nio.file.Files.getAttribute(target.toPath(), "unix:uid")).intValue()
          != app.uid) throw new SecurityException();
      System.out.println("PREPARED");
      System.out.flush();
      System.exit(0);
    } catch (Exception unavailable) {
      android.util.Log.e("HomePodBridgeReload", "Private CLI directory unavailable", unavailable);
      System.out.println("UNAVAILABLE");
      System.out.flush();
      System.exit(2);
    }
  }

  private GalaxyReloadPrepare() {}
}
