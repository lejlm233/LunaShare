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

# slf4j-api 在 release 混淆下缺失绑定实现 (org.slf4j.impl.StaticLoggerBinder)；
# 运行时 slf4j 自动降级为 NOP（jmDNS 等仅 warning，不报错），无需引入绑定依赖。
-dontwarn org.slf4j.impl.StaticLoggerBinder
