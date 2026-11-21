# DroidHand — R8 rules for the release build.

# The shell helper is started by name from adb:
#   CLASSPATH=<apk> app_process /system/bin work.alberton.droidhand.shell.ShellMain
# and talks to the app over the IShellHelper binder. Keep it whole and unrenamed.
-keep class work.alberton.droidhand.shell.** { *; }
-keepclassmembers class work.alberton.droidhand.shell.ShellMain { public static void main(java.lang.String[]); }

# Readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp optional platform classes.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
