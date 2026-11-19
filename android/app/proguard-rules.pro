# kotlinx.serialization：保留生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class cn.lemonit.paperplane.**$$serializer { *; }
-keepclassmembers class cn.lemonit.paperplane.** { *** Companion; }
-keepclasseswithmembers class cn.lemonit.paperplane.** { kotlinx.serialization.KSerializer serializer(...); }
# 网页里的 JS 接口
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
