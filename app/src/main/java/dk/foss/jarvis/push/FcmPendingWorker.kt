package dk.foss.jarvis.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dk.foss.jarvis.receivers.PushIngress

/** One-shot recovery for events whose FCM wakeup was missed. */
class FcmPendingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = PushIngress.syncPending(applicationContext).fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() },
    )
}
