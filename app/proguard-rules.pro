# RPCS4 for Android - ProGuard rules.

# The JNI bridge is referenced from C++ (Rpcs4Jni.cpp) by fully qualified
# names. Never rename anything the native side looks up.
-keep class com.rpcs4.android.native.** { *; }

# Keep Compose-friendly defaults
-dontwarn org.jetbrains.annotations
