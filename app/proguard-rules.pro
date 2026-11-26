-keep class com.sysadmindoc.ruledeck.core.model.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }

# Keep manifest-declared entry points
-keep class com.sysadmindoc.ruledeck.RuleDeckApp_NoHilt
-keep class com.sysadmindoc.ruledeck.MainActivity

# Room generated code
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Dao class *
-keep @androidx.room.Entity class *

# Shizuku AIDL stubs, binder hand-off and IPC reflection
-keep class moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class rikka.sui.** { *; }
-keep class com.sysadmindoc.ruledeck.core.power.ShizukuCommandUserService { *; }
-keep interface com.sysadmindoc.ruledeck.core.power.IShizukuCommandService { *; }

# RE2J internals (uses sun.misc.Unsafe fallback)
-dontwarn com.google.re2j.**
-keep class com.google.re2j.** { *; }

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
