package ru.liftzip.mail

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException

/** Draft-owned files and preparation state survive leaving the editor and process restarts. */
class ForwardWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val draftId=inputData.getString("draft")?:return Result.failure()
        val repo=(applicationContext as MailApplication).repository
        return try { repo.prepareForward(draftId);Result.success() }
        catch(e: CancellationException){throw e}
        catch(e: Exception){repo.forwardFailed(draftId,friendlyError(e));Result.success()}
    }
    companion object {
        private fun name(id: String)="mail-forward-$id"
        fun enqueue(context: Context,id: String,policy: ExistingWorkPolicy=ExistingWorkPolicy.KEEP) = WorkManager.getInstance(context).enqueueUniqueWork(name(id),policy,
            OneTimeWorkRequestBuilder<ForwardWorker>().setInputData(workDataOf("draft" to id)).build())
        fun cancel(context: Context,id: String) = WorkManager.getInstance(context).cancelUniqueWork(name(id))
    }
}
