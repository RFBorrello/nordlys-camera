# Nordlys R8 rules. Compose, AndroidX, coroutines and Coil ship their own
# consumer rules; these cover what is specific to this app.

# Keep line numbers so on-device crash reports (CrashReportSheet) stay readable
# against the mapping.txt attached to each release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Enum names are persisted in SharedPreferences (CameraPrefs) and looked up by
# name; keep them stable across releases so saved settings survive updates.
-keepclassmembers enum com.nordlys.camera.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
