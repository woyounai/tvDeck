# Preserve reflected TLS/JNI APIs and test-visible app classes; remove upstream debug logs.
-dontobfuscate
-keep,allowoptimization class io.github.muntashirakon.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.tvdeck.client.** { *; }
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
# Optional desktop LDAP and pre-Android-5 TLS adapters are unused on minSdk 26.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn javax.naming.**
