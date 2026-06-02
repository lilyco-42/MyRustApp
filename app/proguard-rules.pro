# Rust JNI — 保留 native 方法不被 R8 移除
-keep class com.example.myrustapp.NativeLib {
    native <methods>;
}

# Compose — 避免 R8 过度优化导致 Compose 崩溃
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# 保留 Kotlin 序列化（如果有用到）
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 保留行号信息方便调试
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
