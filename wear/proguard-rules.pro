# R8 for the watch app; consumer rules from Compose/Play Services cover the rest.

# Health Services parcelables (ExerciseGoal, DataTypeCondition, ...) are rebuilt from the service's
# Parcel via CREATOR fields and proto-backed members; R8 renames/strips them -> BadParcelableException.
-keep class androidx.health.services.client.** { *; }
-keep class androidx.health.services.client.proto.** { *; }
-keep class * implements android.os.Parcelable { public static final ** CREATOR; }
-keepclassmembers class * extends androidx.health.services.client.data.ProtoParcelable { *; }
-dontwarn androidx.health.services.client.**
