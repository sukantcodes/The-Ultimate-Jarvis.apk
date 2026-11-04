# Keep Gson model classes
-keep class com.jarvis.assistant.data.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep JavascriptInterfaces for WebViews (Cyberpunk Orb & Settings Activity)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Vosk and JNA native libraries & models
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class net.java.dev.jna.** { *; }
# JNA ships optional desktop AWT bridges that are not present on Android; app code does not use them.
-dontwarn java.awt.**

# Keep Room database models
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase


# ── ♪Sukant♪Codes♪ Security Module — keep rules ───────────────────────────────────
-keep class com.jarvis.assistant.security.SecurityManager { *; }
-keep class com.jarvis.assistant.security.DeveloperInfo { *; }
-keep class com.jarvis.assistant.security.AppIdentityVerifier { *; }

# Obfuscate everything else aggressively
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'scodes'

# Remove Log calls in release builds (security: don't leak internals)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# JNI method names are referenced by native llama.cpp symbols.
-keep class com.arm.aichat.internal.InferenceEngineImpl { *; }
