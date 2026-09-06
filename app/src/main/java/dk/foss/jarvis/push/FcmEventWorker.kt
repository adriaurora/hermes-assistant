package dk.foss.jarvis.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dk.foss.jarvis.receivers.PushIngress

class FcmEventWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_EVENT_ID)
        val result = if (id == null || !isValidHermesEventId(id)) Result.success() else when (val outcome = PushIngress.ingestEventOutcome(applicationContext, id)) {
            GateOutcome.FETCH_FAILURE, GateOutcome.DELIVERY_FAILURE, GateOutcome.ACK_FAILURE ->
                if (FcmRetryDecision.shouldRetry(outcome, runAttemptCount)) Result.retry() else Result.failure()
            GateOutcome.FETCH_PERMANENT -> Result.failure()
            GateOutcome.NOTIFIED, GateOutcome.DEDUPED, GateOutcome.DISABLED,
            GateOutcome.ACKED, GateOutcome.NO_DEVICE -> Result.success()
        }
        PushIngress.schedulePendingSync(applicationContext)
        return result
    }

    companion object { const val KEY_EVENT_ID = "event_id" }
}
