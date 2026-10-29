package ru.liftzip.mail

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MailNotifications(private val context: Context) {
    private val manager=context.getSystemService(NotificationManager::class.java)
    private val prefs=context.getSharedPreferences("mail-notifications",Context.MODE_PRIVATE)
    private val details=mutableMapOf<String,String>()
    companion object {
        const val MESSAGE_EXTRA="mail-message-id"
        const val CHANNEL="mail"
        private const val GROUP="new-mail"
        private const val SUMMARY="mail-summary"
        private val lock=Any()
        fun access(context: Context): NotificationAccess {
            val manager=context.getSystemService(NotificationManager::class.java)
            val power=context.getSystemService(PowerManager::class.java)
            val network=context.getSystemService(ConnectivityManager::class.java)
            return NotificationAccess(
                permission=Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED,
                appEnabled=manager.areNotificationsEnabled(),channelEnabled=manager.getNotificationChannel(CHANNEL)?.importance!=NotificationManager.IMPORTANCE_NONE,
                backgroundRestricted=Build.VERSION.SDK_INT>=28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
                batteryExempt=power.isIgnoringBatteryOptimizations(context.packageName),powerSave=power.isPowerSaveMode,deviceIdle=power.isDeviceIdleMode,
                dataRestricted=network.isActiveNetworkMetered && network.restrictBackgroundStatus==ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                standbyRestricted=Build.VERSION.SDK_INT>=28 && runCatching{context.getSystemService(UsageStatsManager::class.java).appStandbyBucket>=45}.getOrDefault(false),
                connected=network.activeNetwork?.let{network.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}==true)
        }
    }
    private fun openApp(id: String?=null)=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW).setData(Uri.parse(NotificationNavigation.uri(id))).putExtra(MESSAGE_EXTRA,id).putExtra("mail-open-inbox",id==null)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun dismissIntent(ids: Collection<String>)=PendingIntent.getBroadcast(context,0,Intent(context,MailNotificationDismissReceiver::class.java)
        .setData(Uri.parse("liftzip-mail://dismiss/"+Uri.encode(ids.sorted().joinToString("|"))))
        .putStringArrayListExtra("ids",ArrayList(ids)),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun channel(){manager.createNotificationChannel(NotificationChannel(CHANNEL,"Новые письма",NotificationManager.IMPORTANCE_DEFAULT).apply{setShowBadge(true)})}
    private fun post(tag: String?,id: Int,notification: android.app.Notification): Boolean {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false
        return try{manager.notify(tag,id,notification);true}catch(_: SecurityException){false}
    }
    fun contentPriorityIds(): Set<String> =synchronized(lock){
        prefs.getStringSet("unread",emptySet()).orEmpty().minus(prefs.getStringSet("dismissed",emptySet()).orEmpty())
    }
    fun dismiss(ids: Set<String>)=synchronized(lock) {
        val pending=prefs.getStringSet("unread",emptySet()).orEmpty()
        val dismissed=prefs.getStringSet("dismissed",emptySet()).orEmpty()+ids.intersect(pending)
        prefs.edit().putStringSet("dismissed",dismissed).apply()
        ids.forEach{manager.cancel("mail:$it",100)}
    }
    fun reconcile(messages: List<MailItem>,arrived: Set<String> = emptySet(),accounts: List<MailAccount> = emptyList(),body: (String)->String?={null})=synchronized(lock) {
        val previous=prefs.getStringSet("unread",emptySet()).orEmpty().toSet()
        val plan=NotificationRules.plan(previous,arrived,messages,prefs.getStringSet("dismissed",emptySet()).orEmpty())
        val posted=prefs.getStringSet("posted",emptySet()).orEmpty().intersect(plan.pending)
        val displayed=prefs.getStringSet("displayed",emptySet()).orEmpty().toSet()
        prefs.edit().putStringSet("unread",plan.pending).putStringSet("dismissed",plan.dismissed).putStringSet("posted",posted).apply()
        details.keys.retainAll(plan.pending)
        val children=plan.messages.take(NotificationRules.MAX_CHILDREN).map{m->if(m.contentCached)m.copy(body=details.getOrPut(m.id){body(m.id)?.take(3000)?:m.body})else m}
        val ids=children.map{it.id}.toSet()
        (displayed-ids).forEach{manager.cancel("mail:$it",100)}
        manager.cancel(100) // Replace the aggregate notification used by versions <= 0.3.2.
        if(children.isEmpty()) {manager.cancel(SUMMARY,100);prefs.edit().putStringSet("displayed",emptySet()).apply();return@synchronized}
        if(!access(context).canPost)return@synchronized
        channel()
        val group=plan.messages.size>1
        val audible=children.firstOrNull{it.id in arrived && it.id !in posted}?.id
        val names=accounts.associate{it.id to it.name}
        val public=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Новая почта").setContentText("Откройте телефон для просмотра").build()
        for(m in children) {
            val account=names[m.accountId].orEmpty()
            val sender=m.sender.ifBlank{m.senderEmail}.ifBlank{"Без отправителя"}
            val builder=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(sender).setContentText(m.subject+" — "+NotificationRules.shortBody(m))
                .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(sender).bigText(m.subject+"\n\n"+NotificationRules.body(m)).setSummaryText(account))
                .setSubText(account).setWhen(m.date).setShowWhen(true).setContentIntent(openApp(m.id)).setDeleteIntent(dismissIntent(listOf(m.id)))
                .setAutoCancel(true).setOnlyAlertOnce(m.id in posted).setSilent(m.id!=audible)
                .setCategory(NotificationCompat.CATEGORY_EMAIL).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            if(group)builder.setGroup(GROUP).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            if(!post("mail:${m.id}",100,builder.build()))return@synchronized
        }
        if(group) {
            val style=NotificationCompat.InboxStyle().setBigContentTitle("Новых писем: ${plan.messages.size}").setSummaryText("Непрочитанная почта")
            children.take(6).forEach{style.addLine(it.sender+" — "+it.subject)}
            if(plan.messages.size>NotificationRules.MAX_CHILDREN)style.addLine("Остальные письма — в приложении")
            post(SUMMARY,100,NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Новых писем: ${plan.messages.size}").setContentText("Разверните группу для просмотра писем").setNumber(plan.messages.size)
                .setStyle(style).setGroup(GROUP).setGroupSummary(true).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setContentIntent(openApp()).setDeleteIntent(dismissIntent(plan.messages.map{it.id})).setAutoCancel(true).setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build())
        }else manager.cancel(SUMMARY,100)
        prefs.edit().putStringSet("posted",posted+ids).putStringSet("displayed",ids).apply()
        (previous-plan.pending).forEach{prefs.edit().remove("preview-error:$it").apply()}
    }
    fun test(): Boolean=synchronized(lock) {
        if(!access(context).canPost)return@synchronized false
        channel()
        post("mail-test",102,NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Проверка уведомлений Lift-Zip").setContentText("Разрешение уведомлений работает")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Это проверка уведомления на этом телефоне. Письмо не отправлялось. При получении почты здесь будут отправитель, тема и начало текста."))
            .setContentIntent(openApp()).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }

    fun history(snapshot: MailSnapshot) {
        val job=snapshot.historyDownload
        if(!job.active){manager.cancel(101);return}
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        manager.createNotificationChannel(NotificationChannel("history","Загрузка истории",NotificationManager.IMPORTANCE_LOW).apply{setShowBadge(false)})
        val ids=job.accountIds.toSet()
        val loaded=snapshot.messages.count{it.localFolder=="inbox" && it.contentCached && it.accountId in ids}
        val total=job.accountIds.takeIf{list->list.all{snapshot.history[it]?.total!=null}}?.sumOf{snapshot.history[it]?.total?:0}
        post(null,101,NotificationCompat.Builder(context,"history").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if(job.status=="waiting")"История почты: ожидание" else "Загрузка истории почты").setContentText(if(total==null)"Сохранены тексты $loaded писем" else "Сохранены тексты $loaded из $total")
            .setProgress(total?:0,loaded,total==null).setOnlyAlertOnce(true).setContentIntent(openApp())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
}

class MailNotificationDismissReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val ids=intent.getStringArrayListExtra("ids")?.toSet().orEmpty()
        if(ids.isEmpty())return
        MailNotifications(context).dismiss(ids)
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch{try{(context.applicationContext as MailApplication).repository.load()}catch(_: Exception){}finally{pending.finish()}}
    }
}
