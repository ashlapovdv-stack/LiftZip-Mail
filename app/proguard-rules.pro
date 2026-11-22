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

# Angus also ships Java SE and GraalVM paths that this Android client does not use.
# MIME images are read as streams and IMAP/SMTP uses the built-in authentication mechanisms.
-dontwarn java.awt.Image
-dontwarn java.awt.Toolkit
-dontwarn javax.security.auth.callback.NameCallback
-dontwarn javax.security.sasl.**
-dontwarn org.graalvm.nativeimage.hosted.**
