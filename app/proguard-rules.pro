-keep class org.fossify.** { *; }
-dontwarn android.graphics.Canvas
-dontwarn org.fossify.**
-dontwarn org.apache.**

# Picasso
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okhttp3.internal.platform.ConscryptPlatform

-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# RenderScript
-keepclasseswithmembernames class * {
native <methods>;
}
-keep class androidx.renderscript.** { *; }

# Reprint
-keep class com.github.ajalt.reprint.module.** { *; }

# ONNX Runtime — the bundled libonnxruntime.so looks up Java classes by name
# via JNI GetMethodID(); without these keeps R8 strips/renames them and the
# native code aborts with "java_class == null". Caught by a SIGABRT crash in
# OrtSession.run -> convertToTensorInfo on first CLIP encode.
-keep class ai.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ML Kit (image labeling + text recognition) — similar reflective lookups
# from the bundled native model loader.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.mlkit.**

# AndroidX Media3 — uses reflection for some codec selection paths.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
