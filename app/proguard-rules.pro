# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class dev.butan.** {
    *** Companion;
}
-keepclasseswithmembers class dev.butan.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# BouncyCastle lightweight API is used directly.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
