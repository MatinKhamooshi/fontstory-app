# Strip debug/verbose logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
