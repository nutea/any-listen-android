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
# EncryptedSharedPreferences / Tink register key managers through Config.register().
# security-crypto ships empty consumer rules ("safe to shrink"); keep the graph that
# Application.onCreate uses so a minified release cannot fail before first frame.
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
