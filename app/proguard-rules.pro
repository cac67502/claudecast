# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.aide.claudecast.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
