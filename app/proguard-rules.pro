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
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
