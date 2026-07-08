# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Hilt
-keep class dagger.hilt.**
-keep class javax.inject.**
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.gastos.**$$serializer { *; }
-keepclassmembers class com.gastos.** {
    *** Companion;
}
-keepclasseswithmembers class com.gastos.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Google AI
-keep class com.google.ai.client.generativeai.** { *; }

# MediaPipe
-keep class com.google.mediapipe.** { *; }

# CameraX
-keep class androidx.camera.** { *; }

# Coil
-keep class coil.** { *; }

# Gson (usado por Google Sheets API y Backup CSV/JSON opcional)
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.google.api.client.json.gson.** { *; }
-dontwarn sun.misc.**
-keep class com.google.api.services.sheets.v4.** { *; }
-keep class com.google.api.client.googleapis.extensions.android.gms.auth.** { *; }

# OkHttp / Okio (necesario para Play Services Auth y MediaPipe)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
