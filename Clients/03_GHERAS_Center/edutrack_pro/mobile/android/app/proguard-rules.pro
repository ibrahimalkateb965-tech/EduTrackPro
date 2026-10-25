# EduTrack Pro — ProGuard / R8 Rules

# 1. Kotlin & Coroutines
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, SourceFile, LineNumberTable
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# 2. Kotlinx Serialization
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable class *;
    void <init>(int, ...);
}

# 3. Room Database & DAOs
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep class sa.gheras.edutrack.data.local.db.** { *; }
-keep class sa.gheras.edutrack.data.local.entity.** { *; }
-keep interface sa.gheras.edutrack.data.local.dao.** { *; }

# 4. Retrofit, OkHttp & Data Transfer Objects (DTOs)
-dontnote retrofit2.Platform
-dontwarn retrofit2.Platform$Java8
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep class sa.gheras.edutrack.data.remote.dto.** { *; }
-keep interface sa.gheras.edutrack.data.remote.api.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# 5. WorkManager & Outbox Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class sa.gheras.edutrack.data.outbox.OutboxWorker { *; }

# 6. AndroidX Security & Google Tink Cryptography
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }

# 7. Coil Image Loader
-dontwarn coil.**

# 8. Strip Verbose Logs in Production
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
