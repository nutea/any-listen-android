-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
-keep class io.github.nutea.anylisten.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** {
    *** Companion;
}
-keep class androidx.media3.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.session.** { *; }
-keep class androidx.datastore.** { *; }
# EncryptedSharedPreferences calls AeadConfig / DeterministicAeadConfig / AndroidKeysetManager.
# Do not keep all of Tink: that retains optional KeysDownloader and fails R8 on missing
# com.google.api.client / Joda classes that are not on the classpath.
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
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
