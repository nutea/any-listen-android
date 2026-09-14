# EncryptedSharedPreferences / Tink. security-crypto ships empty consumer rules.
# Do not keep all of Tink: that retains optional KeysDownloader and fails R8 on
# missing com.google.api.client / Joda classes that are not on the classpath.
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.Aead { *; }
-keep class com.google.crypto.tink.DeterministicAead { *; }
-keep class com.google.crypto.tink.KeysetHandle { *; }
-keep class com.google.crypto.tink.KeyTemplate { *; }
-keep class com.google.crypto.tink.aead.** { *; }
-keep class com.google.crypto.tink.daead.** { *; }
-keep class com.google.crypto.tink.integration.android.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-dontwarn com.google.crypto.tink.util.KeysDownloader
