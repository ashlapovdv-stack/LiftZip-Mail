package ru.liftzip.mail

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal data class PollWorkState(
    val runtime: BackgroundRuntime,
    val workId: String?=null,
    val generation: Int?=null,
    val workState: String=runtime.state
) {
    fun description(): String = buildString {
        append(workState)
        if(workId!=null)append(" · задание ").append(workId).append(" · редакция ").append(generation)
        append(" · повторов ").append(runtime.attempt)
        if(runtime.nextRunAt>0) {
            val date=SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z",Locale.ROOT).format(Date(runtime.nextRunAt))
            append(" · можно запустить с ").append(date)
        }
        runtime.stopReason?.let{append(" · код остановки ").append(it)}
    }
}

class MailWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as MailApplication
        val repo=app.repository
        val journal=app.backgroundJournal
        val test=inputData.getBoolean("background-test",false)
        val source=if(test)BackgroundSource.TEST else BackgroundSource.AUTOMATIC
        val attempt=runAttemptCount+1
        val token="$id:${java.util.UUID.randomUUID()}"
        val job="задание ${id.toString().take(8)}"
        var checked=false
        journal.record(source,BackgroundStage.STARTED,attempt,detail=job)
        return try {
            repo.load()
            if(!test && repo.snapshot.value.poll.backgroundMinutes==0 || repo.snapshot.value.accounts.none{it.enabled}) {
                journal.record(source,BackgroundStage.SKIPPED,attempt,detail=job)
                return Result.success()
            }
            repo.backgroundStarted(token,if(test)"test" else "auto")
            val result=repo.sync(warmBodies=false)
            val retry=NotificationRules.shouldRetry(result)
            repo.backgroundFinished(token,if(retry)"retry" else "success",result.errors.joinToString("\n"),result.newUnread)
            checked=true
            val stage=when {
                result.dnsFailed->BackgroundStage.DNS_RETRY
                retry->BackgroundStage.RETRY
                else->BackgroundStage.SUCCESS
            }
            journal.record(source,stage,attempt,result.newUnread,job)
            FolderWorker.enqueue(applicationContext);StorageWorker.enqueue(applicationContext)
            // Try warming bodies while Android still grants a network window.
            try {
                val bodies=repo.prefetchBodies()
                if(bodies.pending>0)NotificationPreviewWorker.enqueue(applicationContext)
            }catch(e: CancellationException){throw e}catch(_: Exception){
                NotificationPreviewWorker.enqueue(applicationContext)
            }
            if(retry)Result.retry()else Result.success()
        }catch(e: CancellationException){
            if(!checked) {
                journal.record(source,BackgroundStage.STOPPED,attempt,detail=job)
                withContext(NonCancellable){runCatching{repo.backgroundFinished(token,"stopped")}}
            }
            throw e
        }catch(e: Exception){
            if(!checked) {
                journal.record(source,if(e.isMailDnsFailure())BackgroundStage.DNS_RETRY else BackgroundStage.RETRY,attempt,detail=job)
                repo.backgroundFinished(token,"retry",friendlyError(e))
            }
            Result.retry()
        }
    }
    companion object {
        const val NAME="mail-poll"
        const val TEST_NAME="mail-background-test"
        fun test(context: Context){
            val work=OneTimeWorkRequestBuilder<MailWorker>().setInputData(workDataOf("background-test" to true))
                .setInitialDelay(10,TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(TEST_NAME,ExistingWorkPolicy.KEEP,work)
        }
        internal fun observePeriodic(context: Context)=WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NAME)
            .map { infos->
                val active=infos.firstOrNull{it.state==WorkInfo.State.RUNNING}?:infos.firstOrNull{!it.state.isFinished}
                val observed=active?:infos.singleOrNull()
                if(observed==null)PollWorkState(BackgroundRuntime("STOPPED"))
                else PollWorkState(
                    BackgroundRuntime(active?.state?.name?:"STOPPED",observed.runAttemptCount,
                        observed.nextScheduleTimeMillis.takeIf{it<Long.MAX_VALUE}?:0,
                        observed.stopReason.takeIf{it!=WorkInfo.STOP_REASON_NOT_STOPPED}),
                    observed.id.toString().take(8),observed.generation,observed.state.name
                )
            }.distinctUntilChanged()

        internal fun periodicRequest(minutes: Int)=PeriodicWorkRequestBuilder<MailWorker>(minutes.coerceAtLeast(15).toLong(),TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()

        fun schedule(context: Context,reason: String="настройки или аккаунты") {
            val minutes=context.getSharedPreferences("mail-settings",Context.MODE_PRIVATE).getInt("background-minutes",15)
            if(minutes==0){cancel(context,reason);return}
            changeSchedule(context,"интервал ${minutes.coerceAtLeast(15)} мин · $reason") { manager->
                manager.enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.UPDATE,periodicRequest(minutes))
            }
        }

        fun cancel(context: Context,reason: String="проверка выключена или нет аккаунтов") {
            changeSchedule(context,"отмена · $reason"){it.cancelUniqueWork(NAME)}
        }

        private fun changeSchedule(context: Context,detail: String,change: (WorkManager)->Operation) {
            val journal=(context.applicationContext as MailApplication).backgroundJournal
            journal.record(BackgroundSource.SCHEDULER,BackgroundStage.REQUESTED,detail=detail)
            val operation=try {
                change(WorkManager.getInstance(context))
            }catch(e: RuntimeException) {
                journal.record(BackgroundSource.SCHEDULER,BackgroundStage.SCHEDULE_FAILED,detail=detail)
                throw e
            }
            operation.result.addListener({
                val stage=if(runCatching{operation.result.get()}.isSuccess)BackgroundStage.APPLIED else BackgroundStage.SCHEDULE_FAILED
                journal.record(BackgroundSource.SCHEDULER,stage,detail=detail)
            },ContextCompat.getMainExecutor(context))
        }
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

class NotificationPreviewWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val repo=(applicationContext as MailApplication).repository
        return try {
            repo.load()
            val result=repo.prefetchBodies(120_000)
            when{
                result.pending==0->Result.success()
                result.eligible->{enqueue(applicationContext,ExistingWorkPolicy.APPEND_OR_REPLACE);Result.success()}
                else->Result.retry()
            }
        }catch(e: CancellationException){throw e}catch(_: Exception){Result.retry()}
    }
    companion object {
        fun enqueue(context: Context,policy: ExistingWorkPolicy=ExistingWorkPolicy.APPEND_OR_REPLACE) {
            val work=OneTimeWorkRequestBuilder<NotificationPreviewWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("mail-notification-previews",policy,work)
        }
    }
}
