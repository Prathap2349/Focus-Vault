-keep class com.focusvault.app.data.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.Query *;
    @androidx.room.Insert *;
    @androidx.room.Delete *;
    @androidx.room.Update *;
}
