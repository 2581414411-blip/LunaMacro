# Nexus Agent ProGuard 规则

# Shizuku
-keep class rikka.shizuku.** { *; }

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Moshi
-keep class com.squareup.moshi.** { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
