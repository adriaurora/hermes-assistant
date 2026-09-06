package dk.foss.jarvis.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dk.foss.jarvis.hermes.EventFetchException
import dk.foss.jarvis.receivers.PushIngress
import dk.foss.jarvis.push.RpcErrorClass
import dk.foss.jarvis.push.RpcRetryPolicy

/** One-shot recovery for events whose FCM wakeup was missed. */
class FcmPendingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = PushIngress.syncPending(applicationContext).fold(
        onSuccess = { Result.success() },
        onFailure = { error ->
            val rpc = error as? EventFetchException
            if (RpcRetryPolicy.classify(rpc?.kind, rpc?.statusCode, rpc?.rpcCode) == RpcErrorClass.PERMANENT) {
                Result.failure()
            } else if (runAttemptCount < 5) {
                Result.retry()
            } else {
                Result.failure()
            }
        },
    )
}