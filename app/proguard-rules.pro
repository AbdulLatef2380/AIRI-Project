# ── AIRI Production ProGuard / R8 Rules ──────────────────────────────────────
#
# IMPORTANT: The previous rule was:
#   -keep class com.airi.assistant.** { *; }
# This disabled ALL dead-code elimination for the entire app — defeating the
# purpose of R8. It has been replaced with targeted rules below.

# ── JNI bridge — must be kept or the native layer cannot call Kotlin ──────────
-keep class com.airi.assistant.ai.LlamaBridge { *; }
-keepclassmembers class * {
    native <methods>;
}

# ── Room — entities and DAOs must survive shrinking ───────────────────────────
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }

# ── Kotlin serialization / reflection ────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }

# ── Coroutines ────────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── Compose — keep generated composable infrastructure ───────────────────────
-keep class androidx.compose.** { *; }
-keep @androidx.compose.runtime.Composable class * { *; }

# ── WorkManager — worker classes instantiated by reflection ──────────────────
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# ── Firebase / Google Services ────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# ── Accessibility Service — must be kept for system to bind ──────────────────
-keep class com.airi.assistant.accessibility.service.AiriAccessibilityService { *; }

# ── Application entry points (Android framework reflection) ───────────────────
-keep class com.airi.assistant.AiriApplication { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.content.ContentProvider { *; }

# ── Voice / ONNX / Vosk ──────────────────────────────────────────────────────
-keep class org.vosk.** { *; }
-keep class ai.onnxruntime.** { *; }

# ── Porcupine / Picovoice ────────────────────────────────────────────────────
-keep class ai.picovoice.** { *; }
-keep class com.picovoice.** { *; }

# ── JSON parsing — field names accessed by reflection ────────────────────────
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── BuildConfig ───────────────────────────────────────────────────────────────
-keep class com.airi.assistant.BuildConfig { *; }

# ── Suppress noisy R8 notes for well-known libraries ─────────────────────────
-dontnote kotlinx.coroutines.**
-dontnote kotlin.reflect.**
-dontnote androidx.compose.**
