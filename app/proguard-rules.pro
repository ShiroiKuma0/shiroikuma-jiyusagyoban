-keep class com.opentasker.core.model.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }

# Keep Application class and Hilt-generated classes
-keep class com.opentasker.app.OpenTaskerApp
-keep class com.opentasker.app.Hilt_OpenTaskerApp
-keep class com.opentasker.app.MainActivity
-keep class * implements dagger.hilt.android.HiltAndroidApp
