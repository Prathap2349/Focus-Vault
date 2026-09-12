# Add project specific ProGuard rules here.
-keep class com.focusvault.app.data.** { *; }
-keep class com.focusvault.app.appwidget.** { *; }
-keep class com.focusvault.app.manager.** { *; }
-keep class com.focusvault.app.service.** { *; }
-keep class com.focusvault.app.receiver.** { *; }
-keep class com.focusvault.app.ui.** { *; }
-keep class com.focusvault.app.util.** { *; }

# Room SQLite & KSP reflection
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
