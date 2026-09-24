# FrpcAndroid ProGuard Rules

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep frpc config model classes
-keep class com.frpc.android.model.** { *; }
-keep class com.frpc.android.config.** { *; }
