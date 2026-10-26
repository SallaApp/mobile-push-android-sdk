# SallaPush consumer rules, applied to the host app's R8/ProGuard build.
#
# FlushWorker is created by WorkManager through reflection (also through a host's HiltWorkerFactory fallback), so the
# class and its (Context, WorkerParameters) constructor must survive shrinking. Manifest components (the FCM service,
# the open and permission activities, the dismiss receiver) are kept by AAPT; nothing else needs a rule.
-keep class com.salla.push.internal.work.FlushWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# WorkManager 2.9.1 brings Room 2.5, whose own rule keeps WorkManager's database class but not the constructor Room
# calls by reflection. R8's strict full mode, the default from the Android Gradle plugin 9, then removes it, and every
# start of a minified app crashes in WorkManager's initializer. Newer Room keeps it itself.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
