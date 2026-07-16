-keep class com.opentasker.core.model.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }

# Keep manifest-declared entry points
-keep class com.opentasker.app.OpenTaskerApp_NoHilt
-keep class com.opentasker.app.MainActivity

# Room generated code
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Dao class *
-keep @androidx.room.Entity class *

# Shizuku AIDL stubs and IPC reflection
-keep class dev.rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# Native key grabber (Shizuku UserService). The JNI symbols are derived from the class + method names,
# and the service is instantiated by name inside the privileged process — so it must NOT be renamed or
# stripped. onNativeKey is called from JNI by name.
-keep class com.opentasker.core.input.KeyGrabberService {
    <init>(...);
    native <methods>;
    void onNativeKey(int, int);
}
-keep class com.opentasker.core.input.IKeyGrabber* { *; }

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
