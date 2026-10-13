# R8 full mode. Everything is reached from code, the manifest or Compose, so very little is needed here.
# commonmark loads nothing reflectively; keep its extension entry points just in case of service loading.
-keep class org.commonmark.ext.** { *; }
-dontwarn org.commonmark.**
# Line numbers help read crash logs from `adb logcat` (there is no crash upload).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
