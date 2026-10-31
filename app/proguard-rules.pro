# Room & SQLite keep rules for R8 full mode
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# OkHttp rules for low-RAM shrunk release builds
-dontwarn okhttp3.**
-dontwarn okio.**
