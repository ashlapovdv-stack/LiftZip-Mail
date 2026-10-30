package ru.liftzip.mail

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Runtime state is deliberately not persisted: a killed service must never appear connected. */
data class FastMailStatus(val enabled: Boolean=false,val running: Boolean=false,val starting: Boolean=false,val accounts: Map<String,String> = emptyMap(),val error: String="",val lastEvent: Long=0)
object FastMailRules {
    fun mayStart(enabled: Boolean,visible: Boolean,active: Boolean,running: Boolean)=enabled && visible && active && !running
    fun backoff(attempt: Int)=minOf(60_000L,1_000L*(1L shl attempt.coerceIn(0,6)))
    fun fingerprint(a: MailAccount)=a.id+"|"+AuthRules.serverKey(a)+"|"+a.secret+"|"+a.authKind.name
}
class FastMailController(private val app: MailApplication) {
    private val prefs=app.getSharedPreferences("mail-settings",Context.MODE_PRIVATE)
    private val mutable=MutableStateFlow(FastMailStatus(enabled=prefs.getBoolean("fast-mail",false)))
    val state=mutable.asStateFlow()
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("fast-mail",enabled).apply()
        mutable.update{it.copy(enabled=enabled,error="")}
        if(enabled)ensure()else {app.stopService(Intent(app,FastMailService::class.java));mutable.update{it.copy(starting=false,running=false,accounts=emptyMap())}}
    }
    @Synchronized fun ensure() {
        val s=state.value
        if(!FastMailRules.mayStart(s.enabled,app.visible,app.repository.snapshot.value.accounts.any{it.enabled},s.running || s.starting))return
        mutable.update{it.copy(starting=true,error="")}
        try{ContextCompat.startForegroundService(app,Intent(app,FastMailService::class.java))}
        catch(_: Exception){mutable.update{it.copy(starting=false,error="Android пока не разрешил быстрый режим. Обычная фоновая проверка остаётся по расписанию.")}}
    }
    fun running(){mutable.update{it.copy(running=true,starting=false,error="",accounts=emptyMap())}}
    fun stopped(reason: String=""){mutable.update{it.copy(running=false,starting=false,accounts=emptyMap(),error=reason.ifBlank{it.error})}}
    fun account(id: String,status: String){mutable.update{it.copy(accounts=it.accounts+(id to status))}}
    fun event(){mutable.update{it.copy(lastEvent=System.currentTimeMillis())}}
}

/** Opt-in, visible data synchronization; WorkManager remains the fallback when Android stops it. */
class FastMailService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val app get()=application as MailApplication
    private val events=Channel<String>(Channel.CONFLATED)
    private var started=false
    private var foregroundReady=false
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onCreate() {
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Быстрое получение",NotificationManager.IMPORTANCE_LOW).apply{setShowBadge(false);description="Тихое уведомление работающей синхронизации";setSound(null,null)})
        val open=PendingIntent.getActivity(this,7201,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_mail).setContentTitle("Почта · быстрое получение")
            .setContentText("Ожидаем изменения на почтовых серверах").setContentIntent(open).setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW).build()
        try{ServiceCompat.startForeground(this,7201,notification,if(Build.VERSION.SDK_INT>=29)ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0);foregroundReady=true}
        catch(_: Exception){app.fastMail.stopped("Не удалось запустить быстрый режим. Работает обычная проверка.");stopSelf()}
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(!foregroundReady || !app.fastMail.state.value.enabled){stopSelf();return START_NOT_STICKY}
        if(started)return START_NOT_STICKY
        started=true;app.fastMail.running()
        scope.launch {
            for(id in events) {
                delay(250)
                try {
                    // Refresh all enabled accounts: conflation must not lose a burst from another mailbox.
                    app.repository.sync(warmBodies=false)
                    app.repository.prefetchBodies(45_000)
                    app.fastMail.event()
                }catch(e: CancellationException){throw e}catch(_: Exception){/* Each watcher reconnects; periodic work remains scheduled. */}
            }
        }
        scope.launch {
            app.repository.load()
            app.repository.snapshot.map{it.accounts.filter{a->a.enabled}.map{a->a to FastMailRules.fingerprint(a)}}.distinctUntilChangedBy{it.map{p->p.second}}.collectLatest{accounts->
                if(accounts.isEmpty()){withContext(Dispatchers.Main){stopSelf()};return@collectLatest}
                supervisorScope {accounts.forEach{(a,_)->launch {watch(a)}}}
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun watch(a: MailAccount) {
        var failures=0
        while(currentCoroutineContext().isActive) {
            try {
                app.fastMail.account(a.id,"Подключение…")
                app.repository.watchInbox(a,onConnected={idle->app.fastMail.account(a.id,if(idle)"IMAP IDLE" else "Проверка раз в минуту");events.trySend(a.id)},onChanged={events.trySend(a.id)})
                failures=0
            }catch(e: CancellationException){throw e}catch(e: Exception){
                app.fastMail.account(a.id,friendlyError(e));delay(FastMailRules.backoff(failures++))
            }
        }
    }
    override fun onTimeout(startId: Int,fgsType: Int) {
        app.fastMail.stopped("Android завершил быстрый режим по лимиту времени. Работает обычная фоновая проверка. Откройте приложение для возобновления.")
        scope.cancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy(){scope.cancel();events.close();app.fastMail.stopped();super.onDestroy()}
    companion object {const val CHANNEL="mail-fast-sync"}
}
