package ru.liftzip.mail

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class ActionWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val repo=(applicationContext as MailApplication).repository
        return try {
            repo.load();repo.flushActions(120_000)
            repo.nextActionDelay()?.let{retry(applicationContext,it)}
            Result.success()
        }catch(e: CancellationException){throw e}catch(_: Exception){Result.retry()}
    }
    companion object {
        const val NAME="mail-actions"
        fun enqueue(context: Context) {
            val work=OneTimeWorkRequestBuilder<ActionWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME,ExistingWorkPolicy.APPEND_OR_REPLACE,work)
        }
        fun retry(context: Context,delayMs: Long) {
            val work=OneTimeWorkRequestBuilder<ActionWorker>().setInitialDelay(delayMs.coerceAtLeast(30_000),TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("mail-actions-retry",ExistingWorkPolicy.KEEP,work)
        }
    }
}

class FolderWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val repo=(applicationContext as MailApplication).repository
        return try {
            repo.load()
            var discoveryFailed=false
            for(account in repo.snapshot.value.accounts.filter{it.enabled}) {
                try { repo.discoverFolders(account.id,maxAgeMs=FolderRules.DISCOVERY_MAX_AGE_MS) }
                catch(e: CancellationException){throw e}
                catch(_: Exception){discoveryFailed=true}
            }
            val started=android.os.SystemClock.elapsedRealtime()
            var more=true
            while(more && android.os.SystemClock.elapsedRealtime()-started<120_000)more=repo.folderStep()
            if(discoveryFailed || !more && repo.pendingFolderHistory())return Result.retry()
            if(more)enqueue(applicationContext)
            Result.success()
        }catch(e: CancellationException){throw e}catch(_: Exception){Result.retry()}
    }
    companion object {
        fun enqueue(context: Context) {
            val work=OneTimeWorkRequestBuilder<FolderWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("mail-folders",ExistingWorkPolicy.APPEND_OR_REPLACE,work)
        }
    }
}
