package ru.liftzip.mail

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

data class UiState(val loaded: Boolean=false,val accounts: List<MailAccount> = emptyList(),val messages: List<MailItem> = emptyList(),val history: Map<String,InboxHistory> = emptyMap(),val defaultAccountId: String?=null,
    val poll: PollSettings=PollSettings(),val historyDownload: HistoryDownload=HistoryDownload(),val checks: Map<String,SyncCheck> = emptyMap(),val historyWork: HistoryWorkState=HistoryWorkState.UNKNOWN,
    val background: BackgroundCheck=BackgroundCheck(),val backgroundRuntime: BackgroundRuntime=BackgroundRuntime(),val notificationAccess: NotificationAccess=NotificationAccess(),val bodyCache: BodyCacheStatus=BodyCacheStatus(),
    val opened: MailItem?=null,val searchQuery: String="",val searchIds: Set<String> = emptySet(),val searching: Boolean=false,
    val refreshing: Boolean=false,val unreadRefreshing: Boolean=false,val busy: Boolean=false,val notice: ServiceNotice?=null,
    val forwarding: Map<String,ForwardPlan> = emptyMap(),val attachmentBusy: Set<String> = emptySet(),val autoHidePanels: Boolean=true,val folders: List<ServerFolder> = emptyList(),val actions: List<MailAction> = emptyList(),val storage: StorageStatus=StorageStatus(),val fastMail: FastMailStatus=FastMailStatus(),val style: MailStyle=MailStyle.STANDARD,
    val openingError: Pair<String,String>?=null)

private data class UnreadScope(val enabled: Boolean=false,val accountId: String?=null)

class MailViewModel(app: Application): AndroidViewModel(app) {
    private val repo=(app as MailApplication).repository
    private val mutable=MutableStateFlow(UiState())
    private var foreground: Job?=null
    private var checking: Job?=null
    private var opening: Job?=null
    private var folderOpening: Job?=null
    private var folderTarget: Triple<MailFolder,String?,String?>?=null
    private var folderView=Triple(MailFolder.INBOX,null as String?,null as String?)
    private val query=MutableStateFlow("")
    private val unreadScope=MutableStateFlow(UnreadScope())
    val state=mutable.asStateFlow()
    init {
        viewModelScope.launch{(getApplication<Application>() as MailApplication).fastMail.state.collect{value->mutable.update{it.copy(fastMail=value)}}}
        viewModelScope.launch{repo.forwardingProgress.collect{progress->mutable.update{it.copy(forwarding=progress)}}}
        viewModelScope.launch {
            combine(WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(MailWorker.NAME),WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(MailWorker.TEST_NAME)){infos,tests->infos to tests}.collect{(infos,tests)->
                val active=infos.firstOrNull{it.state==androidx.work.WorkInfo.State.RUNNING}?:infos.firstOrNull{!it.state.isFinished}
                val test=tests.firstOrNull{!it.state.isFinished}?:tests.firstOrNull()
                val runtime=(if(active==null)BackgroundRuntime("STOPPED")else BackgroundRuntime(active.state.name,active.runAttemptCount,active.nextScheduleTimeMillis.takeIf{it<Long.MAX_VALUE}?:0,
                    active.stopReason.takeIf{it!=androidx.work.WorkInfo.STOP_REASON_NOT_STOPPED})).copy(testState=test?.state?.name?:"STOPPED")
                mutable.update{it.copy(backgroundRuntime=runtime)}
            }
        }
        viewModelScope.launch {
            combine(repo.snapshot.map{it.historyDownload.id}.distinctUntilChanged(),WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(HistoryWorker.NAME)){id,infos->
                HistoryRules.workState(infos.filter{HistoryRules.acceptsGeneration(it.tags,id)}.map{it.state.name})
            }.distinctUntilChanged().collect{work->mutable.update{it.copy(historyWork=work)}}
        }
        viewModelScope.launch {
            repo.snapshot.collect { _->(getApplication<Application>() as MailApplication).fastMail.ensure();mutable.update { val snapshot=repo.snapshot.value;it.copy(accounts=snapshot.accounts,messages=snapshot.messages,history=snapshot.history,defaultAccountId=snapshot.defaultAccountId,
                poll=snapshot.poll,historyDownload=snapshot.historyDownload,checks=snapshot.checks,background=snapshot.background,bodyCache=snapshot.bodyCache,autoHidePanels=snapshot.autoHidePanels,folders=snapshot.folders,actions=snapshot.actions,storage=snapshot.storage,style=snapshot.style) } }
        }
        viewModelScope.launch {
            combine(query,repo.snapshot){q,snapshot->q to snapshot.messages}.distinctUntilChanged().collectLatest{(text,_)->
                mutable.update{it.copy(searchQuery=text,searchIds=if(text==it.searchQuery)it.searchIds else emptySet(),searching=text.isNotBlank())}
                if(text.isBlank())mutable.update{it.copy(searchIds=emptySet(),searching=false)}else {
                    delay(200)
                    try {val ids=repo.search(text);mutable.update{it.copy(searchIds=ids,searching=false)}}
                    catch(e: CancellationException){throw e}catch(e: Exception){mutable.update{it.copy(searchIds=emptySet(),searching=false)};notice(friendlyError(e),error=true)}
                }
            }
        }
        viewModelScope.launch {
            combine(unreadScope,state.map{it.loaded}.distinctUntilChanged()){scope,loaded->scope to loaded}.distinctUntilChanged().collectLatest{(scope,loaded)->
                if(!loaded || !scope.enabled)return@collectLatest
                mutable.update{it.copy(unreadRefreshing=true)}
                try {val result=repo.syncUnread(scope.accountId);if(result.errors.isNotEmpty())notice(result.errors.joinToString("\n"),error=true)}
                catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)}
                finally{mutable.update{it.copy(unreadRefreshing=false)}}
            }
        }
        work {
            repo.load()
            val snapshot=repo.snapshot.value
            mutable.update { it.copy(loaded=true,accounts=snapshot.accounts,messages=snapshot.messages,history=snapshot.history,defaultAccountId=snapshot.defaultAccountId,
                poll=snapshot.poll,historyDownload=snapshot.historyDownload,checks=snapshot.checks,background=snapshot.background,bodyCache=snapshot.bodyCache,
                notificationAccess=MailNotifications.access(getApplication()),autoHidePanels=snapshot.autoHidePanels,folders=snapshot.folders,actions=snapshot.actions,storage=snapshot.storage,style=snapshot.style) }
            repo.notificationEnvironmentChanged()
            if(repo.snapshot.value.accounts.any{it.enabled})MailWorker.schedule(getApplication())else MailWorker.cancel(getApplication())
        }
    }
    fun search(text: String){query.value=text}
    fun canOpen(id: String): Boolean {
        val snapshot=repo.snapshot.value
        if(snapshot.messages.any{it.id==id && !it.pendingDelete})return true
        mutable.update{it.copy(messages=snapshot.messages,history=snapshot.history,actions=snapshot.actions)}
        return false
    }
    suspend fun notificationMessage(id: String)=repo.cachedMessage(id)
    suspend fun preserveDraft(draft: ComposeDraft) {
        if(mutable.value.busy)return
        try{repo.saveDraft(draft)}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)}
    }
    fun showUnread(enabled: Boolean,accountId: String?){unreadScope.value=UnreadScope(enabled,accountId)}
    fun openMessage(id: String) {
        opening?.cancel()
        mutable.update{it.copy(opened=it.opened?.takeIf{message->message.id==id && message.contentCached && !MailContent.needsRenderUpdate(message)},openingError=null)}
        opening=viewModelScope.launch {
            try {
                // Show offline content before waiting for content coordination, legacy refresh or LRU writes.
                val cached=repo.cachedMessage(id)?.takeIf{it.contentCached}
                if(cached!=null && !MailContent.needsRenderUpdate(cached))mutable.update{it.copy(opened=cached)}
                val message=repo.message(id)?:error("Письмо не найдено.")
                mutable.update{it.copy(opened=message)}
                read(message)
            }catch(e: CancellationException){throw e}catch(e: Exception){
                val reason=friendlyError(e)
                mutable.update{it.copy(openingError=id to reason)}
                notice(reason,error=true)
            }
        }
    }
    fun openDraft(id: String,onReady: (ComposeDraft)->Unit)=work{onReady(repo.draftForEditing(id))}
    fun visible(active: Boolean) {
        (getApplication<Application>() as MailApplication).visible=active
        if(!active){foreground?.cancel();foreground=null;return}
        if(foreground?.isActive==true)return
        notificationEnvironmentChanged()
        (getApplication<Application>() as MailApplication).fastMail.ensure()
        foreground=viewModelScope.launch {
            state.map{Triple(it.loaded,it.poll.foregroundMinutes,it.accounts.filter{a->a.enabled}.map{a->a.id})}.distinctUntilChanged().collectLatest { (loaded,minutes,ids)->
                if(!loaded || ids.isEmpty())return@collectLatest
                while(isActive){check(false);val view=folderView;if(view.first in setOf(MailFolder.SENT,MailFolder.DRAFTS) || view.third!=null)refreshFolders(view.first,view.second,view.third,false);delay(minutes*60_000L)}
            }
        }
    }
    fun notificationEnvironmentChanged() {
        viewModelScope.launch {
            mutable.update{it.copy(notificationAccess=MailNotifications.access(getApplication()))}
            if(!mutable.value.loaded)return@launch
            try{repo.notificationEnvironmentChanged()}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)}
        }
    }
    suspend fun inlineImage(m: MailItem,file: Attachment)=repo.inlineImage(m,file)
    suspend fun thumbnailImage(m: MailItem,file: Attachment)=repo.thumbnailImage(m,file)
    fun notificationOpened(id: String) {
        viewModelScope.launch {try{repo.dismissNotifications(setOf(id))}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)}}
    }
    fun testNotification()=work {
        notificationEnvironmentChanged()
        if(!repo.testNotification())notice("Разрешите уведомления приложения и канал «Новые письма» в настройках Android.")
        else notice("Проверочное уведомление отправлено в шторку. Письмо не отправлялось.")
    }
    fun testBackground(){MailWorker.test(getApplication());notice("Проверка запланирована. Сверните приложение; результат появится здесь после запуска задания Android.")}
    fun restoreBackground(){
        MailWorker.schedule(getApplication());notificationEnvironmentChanged()
        notice("Расписание фоновой проверки восстановлено")
    }
    fun retryBodies(){viewModelScope.launch{try{repo.retryBodyWarmup()}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)}}}
    fun dismissNotice(id: String){mutable.update{if(it.notice?.id==id)it.copy(notice=null)else it}}
    fun notice(text: String,error: Boolean=false){mutable.update{it.copy(notice=ServiceNotice(text,error))}}
    private fun work(action: suspend ()->Unit) {
        if(mutable.value.busy)return
        mutable.update{it.copy(busy=true)}
        viewModelScope.launch {
            try{action()}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)}
            finally{mutable.update{it.copy(busy=false,loaded=true)}}
        }
    }
    fun addAccount(a: MailAccount,password: String,onDone: ()->Unit) = work {
        repo.addAccount(a,password)
        MailWorker.schedule(getApplication())
        onDone()
        val sync=repo.sync()
        notice(if(sync.errors.isEmpty())"Ящик подключён" else "Ящик добавлен. ${sync.errors.joinToString("\n")}",error=sync.errors.isNotEmpty())
    }
    fun editAccount(a: MailAccount,password: String,onDone: ()->Unit)=work{
        repo.editAccount(a,password);onDone()
        val result=repo.sync()
        notice(if(result.errors.isEmpty())"Настройки ящика сохранены" else "Настройки сохранены. ${result.errors.joinToString("\n")}",error=result.errors.isNotEmpty())
    }
    fun removeAccount(id: String)=work{
        val removed=mutable.value.accounts.find{it.id==id}
        repo.removeAccount(id)
        var revoked=true
        if(removed?.authKind==AuthKind.GOOGLE)revoked=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){runCatching{GoogleMailAuth.disconnect(getApplication(),removed.email)}.isSuccess}
        if(repo.snapshot.value.accounts.none{it.enabled})MailWorker.cancel(getApplication())
        notice(if(revoked)"Ящик отключён от приложения. Письма на сервере сохранены." else "Ящик удалён с устройства. Не удалось отозвать доступ Google; это можно сделать в настройках аккаунта Google.",error=!revoked)
    }
    fun setAccountEnabled(id: String,enabled: Boolean) {
        viewModelScope.launch {
            try {
                repo.setAccountEnabled(id,enabled)
                if(repo.snapshot.value.accounts.any{it.enabled})MailWorker.schedule(getApplication())else MailWorker.cancel(getApplication())
                if(enabled)check(false)
            }catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)}
        }
    }
    fun showFolders(folder: MailFolder,accountId: String?,path: String?) {
        folderView=Triple(folder,accountId,path)
        if(path!=null || folder in setOf(MailFolder.SENT,MailFolder.DRAFTS))refreshFolders(folder,accountId,path,false)
    }
    fun refreshFolders(folder: MailFolder,accountId: String?,path: String?,manual: Boolean=true) {
        val target=Triple(folder,accountId,path)
        if(folderOpening?.isActive==true){if(folderTarget==target)return;folderOpening?.cancel()}
        folderTarget=target
        folderOpening=viewModelScope.launch {
            try {
                repo.discoverFolders(accountId)
                val role=when(folder){MailFolder.SENT->FolderRole.SENT;MailFolder.DRAFTS->FolderRole.DRAFTS;else->null}
                val targets=repo.snapshot.value.folders.filter{(accountId==null || it.accountId==accountId) && if(path!=null)it.path==path else it.role==role}
                for(f in targets)repo.syncFolder(f.accountId,f.path)
                if(manual)notice(if(targets.any{f->repo.snapshot.value.folders.any{it.accountId==f.accountId && it.path==f.path && it.error.isNotBlank()}})"Некоторые папки не обновились. Подробности указаны в папке." else "Папка обновлена")
            }catch(e: CancellationException){throw e}catch(e: Exception){if(manual)notice(friendlyError(e),error=true)}
        }
    }
    fun discoverFolders()=work{repo.discoverFolders()}
    fun startFolderHistories(folders: List<ServerFolder>)=work{repo.startFolderHistories(folders);notice("Загрузка выбранных папок запущена")}
    fun loadFolderHistory(accountId: String,path: String)=work{repo.loadFolderHistory(accountId,path);notice("История папки загружается в фоне")}
    fun pauseFolderHistory(accountId: String,path: String)=work{repo.pauseFolderHistory(accountId,path);notice("Загрузка папки приостановлена")}
    fun retryAction(id: String,confirmed: Boolean=false)=work{repo.retryAction(id,confirmed);notice("Действие возвращено в очередь")}
    fun cancelAction(id: String)=work{repo.cancelAction(id);notice("Ожидающее действие отменено")}
    fun setFastMail(enabled: Boolean){(getApplication<Application>() as MailApplication).fastMail.setEnabled(enabled)}
    fun storageStatus()=work{repo.storageStatus()}
    fun setCacheLimit(value: Int)=work{repo.setCacheLimit(value)}
    fun clearCache()=work{repo.trimCache(clear=true);notice("Кэш очищен. Черновики и Исходящие сохранены.")}
    fun refresh() {check(true)}
    private fun check(manual: Boolean) {
        if(checking?.isActive==true)return
        checking=viewModelScope.launch {
            mutable.update{it.copy(refreshing=true)}
            try {
                val result=repo.sync(if(manual)unreadScope.value.accountId else null)
                val scope=unreadScope.value
                val errors=result.errors.toMutableList()
                if(scope.enabled && !mutable.value.unreadRefreshing)errors+=repo.syncUnread(scope.accountId).errors
                if(manual)notice(if(errors.isEmpty())"Почта обновлена" else errors.distinct().joinToString("\n"),error=errors.isNotEmpty())
            }catch(e: CancellationException){throw e}catch(e: Exception){if(manual)notice(friendlyError(e),error=true)}
            finally{mutable.update{it.copy(refreshing=false)}}
        }
    }
    fun setPoll(poll: PollSettings)=work{repo.setPoll(poll);MailWorker.schedule(getApplication());notice("Интервал проверки почты сохранён")}
    fun setStyle(style: MailStyle){viewModelScope.launch{try{repo.setStyle(style)}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)}}}
    fun setAutoHidePanels(enabled: Boolean){viewModelScope.launch{try{repo.setAutoHidePanels(enabled)}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)}}}
    fun deleteMessages(ids: Set<String>,onRemoved: (Set<String>)->Unit)=work {
        val result=repo.deleteMessages(ids)
        mutable.update{val snapshot=repo.snapshot.value;it.copy(messages=snapshot.messages,history=snapshot.history,actions=snapshot.actions,opened=it.opened?.takeUnless{m->m.id in result.removed})}
        onRemoved(result.removed)
        notice((if(result.removed.isNotEmpty())"К удалению: ${result.removed.size}" else if(result.errors.isEmpty())"Письма уже удалены или перемещены" else "")+
            (if(result.errors.isNotEmpty())(if(result.removed.isNotEmpty())"\n" else "")+result.errors.joinToString("\n")else ""),error=result.errors.isNotEmpty())
    }
    fun startHistory(ids: List<String>)=work{HistoryWorker.enqueue(getApplication(),repo.startHistory(ids));notice("Загрузка истории запущена")}
    fun spamMessages(ids: Set<String>,toInbox: Boolean,onMoved: (Set<String>)->Unit)=work {
        val result=repo.spamMessages(ids,toInbox)
        mutable.update{it.copy(opened=it.opened?.takeUnless{m->m.id in result.removed})}
        onMoved(result.removed)
        val message=if(result.removed.isNotEmpty())"${if(toInbox)"К возврату во Входящие" else "К перемещению в Спам"}: ${result.removed.size}. Действие сохранено в очереди." else "Подходящих писем нет."
        notice((listOf(message)+result.errors).joinToString("\n"),error=result.errors.isNotEmpty())
    }
    fun resumeHistory()=startHistory(mutable.value.historyDownload.accountIds)
    fun pauseHistory()=work{repo.pauseHistory();HistoryWorker.cancel(getApplication());notice("Загрузка приостановлена. Полученные письма сохранены.")}
    fun setDefaultAccount(id: String?)=work{repo.setDefaultAccount(id);notice("Ящик отправителя по умолчанию сохранён")}
    fun reply(message: MailItem,onReady: (ComposeDraft)->Unit)=work{onReady(repo.reply(message))}
    fun forward(message: MailItem,senderId: String,onReady: (ComposeDraft)->Unit)=work{onReady(repo.forward(message,senderId))}
    fun retryForward(id: String){viewModelScope.launch{try{repo.retryForward(id)}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)}}}
    fun removeForwardFile(id: String,key: String){viewModelScope.launch{try{repo.removeForwardFile(id,key)}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)}}}
    fun loadOlder(accountId: String?) = work {
        val before=repo.snapshot.value.messages.count{it.localFolder=="inbox" && (accountId==null || it.accountId==accountId)}
        val result=repo.loadOlder(accountId)
        val added=repo.snapshot.value.messages.count{it.localFolder=="inbox" && (accountId==null || it.accountId==accountId)}-before
        notice(if(result.errors.isNotEmpty())result.errors.joinToString("\n") else if(added>0)"Загружено ещё $added писем" else "Более старых писем нет")
    }
    fun read(m: MailItem){
        if(!m.unread || mutable.value.accounts.none{it.id==m.accountId && it.enabled})return
        viewModelScope.launch { try{val result=repo.markRead(setOf(m.id),unread=false);if(result.errors.isNotEmpty())notice(result.errors.joinToString("\n"),error=true)}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)} }
    }
    fun markRead(ids: Set<String>,unread: Boolean,onChanged: (Set<String>)->Unit)=work{
        val result=repo.markRead(ids,unread);onChanged(result.updated)
        notice(if(result.errors.isEmpty())"Статус изменён у ${result.updated.size} писем" else "Изменено: ${result.updated.size}. ${result.errors.joinToString("\n")}",error=result.errors.isNotEmpty())
    }
    fun star(m: MailItem){
        viewModelScope.launch { try{repo.changeFlags(m,starred=!m.starred)}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)} }
    }
    fun saveDraft(draft: ComposeDraft,onDone: ()->Unit)=work{
        repo.saveDraft(draft)
        onDone();notice("Черновик сохранён")
    }
    fun deleteDraft(id: String,onDone: ()->Unit)=work{
        repo.deleteDraft(id)
        onDone()
    }
    fun send(draft: ComposeDraft,onDone: ()->Unit) {
        MailRules.validateDraft(draft)?.let{notice(it,error=true);return}
        work {
            repo.send(draft);notice("Письмо в Исходящих. Отправка начнётся при подключении к сети.")
            onDone()
        }
    }
    private fun attachmentWork(m: MailItem,file: Attachment,action: suspend ()->Unit){
        val key="${m.id}|${file.path}"
        if(key in mutable.value.attachmentBusy)return
        mutable.update{it.copy(attachmentBusy=it.attachmentBusy+key)}
        viewModelScope.launch{
            try{action()}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e),error=true)}
            finally{mutable.update{it.copy(attachmentBusy=it.attachmentBusy-key)}}
        }
    }
    fun saveOriginal(m: MailItem,uri: Uri) {
        viewModelScope.launch {
            notice("Сохраняем оригинал письма…")
            try{repo.saveOriginal(m,uri);notice("Оригинал письма сохранён")}
            catch(e: CancellationException){throw e}
            catch(e: Exception){notice(friendlyError(e),error=true)}
        }
    }
    fun download(m: MailItem,file: Attachment,uri: Uri)=attachmentWork(m,file){repo.download(m,file,uri);notice("Вложение сохранено")}
    fun openAttachment(m: MailItem,file: Attachment,onOpen: (OpenedAttachment)->Unit)=attachmentWork(m,file){onOpen(repo.openAttachment(m,file))}
}
