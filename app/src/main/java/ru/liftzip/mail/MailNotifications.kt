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

class MailNotifications(private val context: Context) {
    private val manager=context.getSystemService(NotificationManager::class.java)
    private val prefs=context.getSharedPreferences("mail-notifications",Context.MODE_PRIVATE)
    private fun openApp()=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun reconcile(messages: List<MailItem>,arrived: Set<String> = emptySet()) {
        val previous=prefs.getStringSet("unread",emptySet()).orEmpty().toSet()
        val pending=SyncRules.pendingNotifications(previous,arrived,messages)
        prefs.edit().putStringSet("unread",pending).apply()
        if(pending.isEmpty()){manager.cancel(100);return}
        val hasNew=(pending-previous).isNotEmpty()
        // Dismissing an alert must not cause it to reappear on every local save/history page.
        if(!hasNew && manager.activeNotifications.none{it.id==100})return
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        manager.createNotificationChannel(NotificationChannel("mail","Новые письма",NotificationManager.IMPORTANCE_DEFAULT).apply{setShowBadge(true)})
        manager.notify(100,NotificationCompat.Builder(context,"mail").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Непрочитанная почта").setContentText("Новых непрочитанных писем: ${pending.size}")
            .setNumber(pending.size).setContentIntent(openApp()).setAutoCancel(true)
            .setOnlyAlertOnce(!hasNew).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }

    fun history(snapshot: MailSnapshot) {
        val job=snapshot.historyDownload
        if(!job.active){manager.cancel(101);return}
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        manager.createNotificationChannel(NotificationChannel("history","Загрузка истории",NotificationManager.IMPORTANCE_LOW).apply{setShowBadge(false)})
        val ids=job.accountIds.toSet()
        val loaded=snapshot.messages.count{it.localFolder=="inbox" && it.contentCached && it.accountId in ids}
        val total=job.accountIds.takeIf{list->list.all{snapshot.history[it]?.total!=null}}?.sumOf{snapshot.history[it]?.total?:0}
        manager.notify(101,NotificationCompat.Builder(context,"history").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if(job.status=="waiting")"История почты: ожидание" else "Загрузка истории почты").setContentText(if(total==null)"Сохранены тексты $loaded писем" else "Сохранены тексты $loaded из $total")
            .setProgress(total?:0,loaded,total==null).setOnlyAlertOnce(true).setContentIntent(openApp())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
}
