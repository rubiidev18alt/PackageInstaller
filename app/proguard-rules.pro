# Reflection targets used by the vendored ADB library and Shizuku.
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-keep class io.github.muntashirakon.crypto.** { *; }
-keep class moe.shizuku.server.** { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn javax.naming.**
