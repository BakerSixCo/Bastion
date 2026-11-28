# BASTION release shrinking. Libraries reached through JNI or reflection are
# kept whole: R8 cannot see native callers.

# libkiwix / libzim (JNI)
-keep class org.kiwix.** { *; }
# LiteRT-LM (JNI + Kotlin API)
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.** { *; }
# PdfBox-Android (resources loaded by name, fonts, reflection)
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
# Keep native method names generally
-keepclasseswithmembernames class * { native <methods>; }
