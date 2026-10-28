package ru.liftzip.mail

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class MailWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val repo=(applicationContext as MailApplication).repository
        return try {
            val result=repo.sync()
            if(result.newUnread>0)notifyNewMail(result.newUnread)
            // Authentication failures require the user, not endless retries.
            Result.success()
        }catch(e: CancellationException){throw e}catch(e: Exception){Result.retry()}
    }
    private fun notifyNewMail(count: Int) {
        if(Build.VERSION.SDK_INT>=33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("mail","Новые письма",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=PendingIntent.getActivity(applicationContext,0,Intent(applicationContext,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(100,NotificationCompat.Builder(applicationContext,"mail").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Новые письма").setContentText("Непрочитанных новых писем: $count")
            .setContentIntent(intent).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
    companion object {
        fun schedule(context: Context) {
            val work=PeriodicWorkRequestBuilder<MailWorker>(15,TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("mail-poll",ExistingPeriodicWorkPolicy.KEEP,work)
        }
        fun cancel(context: Context)=WorkManager.getInstance(context).cancelUniqueWork("mail-poll")
    }
}
