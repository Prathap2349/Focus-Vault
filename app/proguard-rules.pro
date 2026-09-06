# Add project specific ProGuard rules here.
-keep class com.stayfocused.app.data.** { *; }
-keep class com.stayfocused.app.appwidget.** { *; }
-keep class com.stayfocused.app.manager.** { *; }
-keep class com.stayfocused.app.service.** { *; }
-keep class com.stayfocused.app.receiver.** { *; }
-keep class com.stayfocused.app.ui.** { *; }
-keep class com.stayfocused.app.util.** { *; }

# Room SQLite & KSP reflection
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
