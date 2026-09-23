# Agora SDKs use JNI; keep them intact if minification is enabled.
-keep class io.agora.** { *; }
-dontwarn io.agora.**
