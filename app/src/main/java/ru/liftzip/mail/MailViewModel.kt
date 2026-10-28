package ru.liftzip.mail

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(val loaded: Boolean=false,val accounts: List<MailAccount> = emptyList(),val messages: List<MailItem> = emptyList(),val history: Map<String,InboxHistory> = emptyMap(),val defaultAccountId: String?=null,val busy: Boolean=false,val notice: String?=null)

class MailViewModel(app: Application): AndroidViewModel(app) {
    private val repo=(app as MailApplication).repository
    private val mutable=MutableStateFlow(UiState())
    val state=mutable.asStateFlow()
    init {
        viewModelScope.launch {
            repo.snapshot.collect { snapshot->mutable.update { it.copy(accounts=snapshot.accounts,messages=snapshot.messages,history=snapshot.history,defaultAccountId=snapshot.defaultAccountId) } }
        }
        work {
            repo.load()
            mutable.update { it.copy(loaded=true) }
            if(repo.snapshot.value.accounts.isNotEmpty())MailWorker.schedule(getApplication())
        }
    }
    fun dismissNotice(){mutable.update{it.copy(notice=null)}}
    fun notice(text: String){mutable.update{it.copy(notice=text)}}
    private fun work(action: suspend ()->Unit) {
        if(mutable.value.busy)return
        mutable.update{it.copy(busy=true)}
        viewModelScope.launch {
            try{action()}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e))}
            finally{mutable.update{it.copy(busy=false,loaded=true)}}
        }
    }
    fun addAccount(a: MailAccount,password: String,onDone: ()->Unit) = work {
        repo.addAccount(a,password)
        MailWorker.schedule(getApplication())
        onDone()
        val sync=repo.sync()
        notice(if(sync.errors.isEmpty())"Ящик подключён" else "Ящик добавлен. ${sync.errors.joinToString("\n")}")
    }
    fun removeAccount(id: String)=work{
        repo.removeAccount(id)
        if(repo.snapshot.value.accounts.isEmpty())MailWorker.cancel(getApplication())
        notice("Ящик отключён от приложения. Письма на сервере сохранены.")
    }
    fun refresh() = work {
        val result=repo.sync();notice(if(result.errors.isEmpty())"Почта обновлена" else result.errors.joinToString("\n"))
    }
    fun setDefaultAccount(id: String?)=work{repo.setDefaultAccount(id);notice("Ящик отправителя по умолчанию сохранён")}
    fun forward(message: MailItem,senderId: String,onReady: (ComposeDraft)->Unit)=work{onReady(repo.forward(message,senderId))}
    fun loadOlder(accountId: String?) = work {
        val before=repo.snapshot.value.messages.count{it.localFolder=="inbox" && (accountId==null || it.accountId==accountId)}
        val result=repo.loadOlder(accountId)
        val added=repo.snapshot.value.messages.count{it.localFolder=="inbox" && (accountId==null || it.accountId==accountId)}-before
        notice(if(result.errors.isNotEmpty())result.errors.joinToString("\n") else if(added>0)"Загружено ещё $added писем" else "Более старых писем нет")
    }
    fun read(m: MailItem){
        if(!m.unread)return
        viewModelScope.launch { try{repo.changeFlags(m,unread=false)}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e))} }
    }
    fun star(m: MailItem){
        viewModelScope.launch { try{repo.changeFlags(m,starred=!m.starred)}catch(e: CancellationException){throw e}catch(e: Exception){notice(friendlyError(e))} }
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
        MailRules.validateDraft(draft)?.let{notice(it);return}
        work {
            repo.send(draft);notice("Письмо принято сервером отправителя")
            onDone()
        }
    }
    fun download(m: MailItem,file: Attachment,uri: Uri)=work{repo.download(m,file,uri);notice("Вложение сохранено")}
    fun openAttachment(m: MailItem,file: Attachment,onOpen: (OpenedAttachment)->Unit)=work{onOpen(repo.openAttachment(m,file))}
}
