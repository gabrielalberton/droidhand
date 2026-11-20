# DroidHand Portal — ProGuard/R8 rules.
# Release currently ships unminified (isMinifyEnabled = false); keep OkHttp's
# optional platform classes quiet for when minification is turned on.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
