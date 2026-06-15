-keep class com.opentasker.core.model.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }

# Keep manifest-declared entry points
-keep class com.opentasker.app.OpenTaskerApp_NoHilt
-keep class com.opentasker.app.MainActivity
