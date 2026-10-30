package ru.liftzip.mail

import android.content.Context
import androidx.work.*
import java.io.File

/** Only reproducible server bodies and preview files count toward this budget. */
data class StorageStatus(val limitMb: Int=250,val bodyBytes: Long=0,val attachmentBytes: Long=0,val cachedBodies: Int=0,val checkedAt: Long=0) {
    val bytes get()=bodyBytes+attachmentBytes
}
object CacheRules {
    val choices=listOf(100,250,500,1000,0)
    fun normalized(value: Int)=value.takeIf{it in choices}?:250
    fun bodyBudget(limitMb: Int)=if(limitMb==0)Long.MAX_VALUE else limitMb*1024L*1024*3/5
    fun fileBudget(limitMb: Int)=if(limitMb==0)Long.MAX_VALUE else limitMb*1024L*1024*2/5
    fun evictable(m: MailItem,protected: Set<String>)=FolderRules.remote(m) && m.contentCached && !m.pendingDelete && m.id !in protected
    fun evict(messages: List<MailItem>,protected: Set<String>,budget: Long,clear: Boolean=false): Set<String> {
        var used=messages.filter{FolderRules.remote(it) && it.contentCached}.sumOf{it.cacheBytes.coerceAtLeast(0)}
        return buildSet {
            for(m in messages.filter{evictable(it,protected)}.sortedWith(compareBy<MailItem>{if(it.unread)1 else 0}.thenBy{it.cachedAt}.thenBy{it.date})) {
                if(!clear && used<=budget)break
                add(m.id);used-=m.cacheBytes.coerceAtLeast(0)
            }
        }
    }
    /** A fixed priority window avoids endlessly refetching bodies removed to meet the quota. */
    fun warmSources(messages: List<MailItem>,priority: Set<String>,budget: Long): List<MailItem> {
        if(budget==Long.MAX_VALUE)return messages
        var remaining=budget
        val allowed=buildSet {
            for(m in messages.filter{SyncRules.remoteInbox(it) && !it.pendingDelete}.sortedWith(compareBy<MailItem>{ContentCacheRules.rank(it,priority)}.thenByDescending{it.date})) {
                val cost=m.cacheBytes.takeIf{it>0}?:64*1024L
                if(cost<=remaining){add(m.id);remaining-=cost}
            }
        }
        return messages.filter{!SyncRules.remoteInbox(it) || it.id in allowed}
    }
}

class PreviewStorage(private val root: File) {
    constructor(context: Context):this(File(context.cacheDir,"attachments"))
    fun bytes()=if(root.isDirectory)root.walkTopDown().filter{it.isFile && !it.name.endsWith(".part")}.sumOf{it.length()}else 0L
    fun trim(budget: Long,clear: Boolean=false,now: Long=System.currentTimeMillis()) {
        if(!root.isDirectory)return
        val files=root.walkTopDown().filter{it.isFile && !it.name.endsWith(".part")}.toList()
        var used=files.sumOf{it.length()}
        for(file in files.sortedBy{it.lastModified()}) {
            if(!clear && used<=budget)break
            // A viewer may still be opening a freshly prepared file. Explicit clearing is user initiated.
            if(!clear && now-file.lastModified()<120_000)continue
            val size=file.length();if(file.delete())used-=size
        }
        root.walkBottomUp().filter{it.isDirectory && it!=root}.forEach{if(it.list()?.isEmpty()==true)it.delete()}
    }
}

class StorageWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        return try{(applicationContext as MailApplication).repository.trimCache();Result.success()}
        catch(e: kotlinx.coroutines.CancellationException){throw e}catch(_: Exception){Result.retry()}
    }
    companion object {
        fun enqueue(context: Context){WorkManager.getInstance(context).enqueueUniqueWork("mail-cache-trim",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<StorageWorker>().build())}
    }
}
