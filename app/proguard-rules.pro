# 项目 ProGuard/R8 规则
# 安全目标：release 混淆，且不因混淆暴露任何关于作者/使用者的信息。

# 保留行号便于线上排错（可选；如需更彻底隐藏源码名可注释掉下一行）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保留 Kotlin 元数据（Compose 反射需要）
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Compose 运行时
-dontwarn androidx.compose.**

# 下载引擎通过 DownloadManager 反射/接口调用，保留站点类与接口方法
-keep class com.java.myapplication.engine.** { *; }
-keep class com.java.myapplication.site.** { *; }
-keep class com.java.myapplication.data.** { *; }

# 保留 ViewModel 构造相关
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ===== ffmpeg-kit（原生库经 JNI 回调 Java 层，混淆会破坏）=====
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.**

# ===== Cronet（Chromium 网络栈，JNI 回调）=====
-keep class org.chromium.net.** { *; }
-dontwarn org.chromium.net.**