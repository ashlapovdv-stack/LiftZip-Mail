# Serialized UI and draft sessions must remain readable after an APK update.
-keep class ru.liftzip.mail.** implements java.io.Serializable { *; }

# WorkManager persists worker class names and creates them by reflection.
-keep class ru.liftzip.mail.** extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Jakarta Mail loads protocol and activation providers from service descriptors.
-keep class org.eclipse.angus.mail.** { *; }
-keep class org.eclipse.angus.activation.** { *; }
-keep class * extends jakarta.mail.Provider { *; }
