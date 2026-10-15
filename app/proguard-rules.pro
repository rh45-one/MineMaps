# kotlinx.serialization, OkHttp and androidx.car.app ship consumer rules.
# Keep the engine's classpath resources (atlas, font, licences) addressable by name.
-keepclassmembers class app.minemaps.engine.art.EngineResources { *; }

# Release builds: strip debug/verbose logging entirely (also removes argument evaluation).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
