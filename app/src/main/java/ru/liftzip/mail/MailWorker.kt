package ru.liftzip.mail

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MailWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val repo=(applicationContext as MailApplication).repository
        return try {
            repo.load()
            if(repo.snapshot.value.poll.backgroundMinutes==0)return Result.success()
            repo.sync()
            Result.success()
        }catch(e: CancellationException){throw e}catch(e: Exception){Result.retry()}
    }
    companion object {
        fun schedule(context: Context) {
            val minutes=context.getSharedPreferences("mail-settings",Context.MODE_PRIVATE).getInt("background-minutes",15)
            if(minutes==0){cancel(context);return}
            val work=PeriodicWorkRequestBuilder<MailWorker>(minutes.coerceAtLeast(15).toLong(),TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            // UPDATE applies a changed interval instead of retaining the old request forever.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("mail-poll",ExistingPeriodicWorkPolicy.UPDATE,work)
        }
        fun cancel(context: Context)=WorkManager.getInstance(context).cancelUniqueWork("mail-poll")
    }
}

class HistoryWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val generation=inputData.getString("generation")?:return Result.failure()
        val repo=(applicationContext as MailApplication).repository
        return try {
            repo.load()
            val failed=mutableSetOf<String>()
            val started=SystemClock.elapsedRealtime()
            while(true) {
                currentCoroutineContext().ensureActive()
                val step=repo.historyStep(generation,failed)
                step.failedAccount?.let{failed+=it}
                if(step.retry)return Result.retry()
                if(!step.more)return Result.success()
                if(SystemClock.elapsedRealtime()-started>=150_000) {
                    // Chunked jobs resume from persisted UIDs without an hours-long worker.
                    repo.waitHistory(generation)
                    enqueue(applicationContext,generation,ExistingWorkPolicy.APPEND_OR_REPLACE)
                    return Result.success()
                }
            }
            @Suppress("UNREACHABLE_CODE") Result.success()
        }catch(e: CancellationException){
            withContext(NonCancellable){runCatching{repo.waitHistory(generation)}}
            throw e
        }catch(e: Exception){repo.waitHistory(generation);Result.retry()}
    }
    companion object {
        const val NAME="mail-history"
        fun cancel(context: Context)=WorkManager.getInstance(context).cancelUniqueWork(NAME)
        fun enqueue(context: Context,generation: String,policy: ExistingWorkPolicy=ExistingWorkPolicy.REPLACE) {
            val work=OneTimeWorkRequestBuilder<HistoryWorker>()
                .setInputData(workDataOf("generation" to generation))
                .addTag(HistoryRules.GENERATION_TAG+generation)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME,policy,work)
        }
    }
}
