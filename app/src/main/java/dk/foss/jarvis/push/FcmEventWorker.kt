package dk.foss.jarvis.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dk.foss.jarvis.receivers.PushIngress

class FcmEventWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_EVENT_ID) ?: return Result.success()
        if (!isValidHermesEventId(id)) return Result.success()
        return when (val outcome = PushIngress.ingestEventOutcome(applicationContext, id)) {
            GateOutcome.FETCH_FAILURE, GateOutcome.DELIVERY_FAILURE, GateOutcome.ACK_FAILURE ->
                if (FcmRetryDecision.shouldRetry(outcome, runAttemptCount)) Result.retry() else Result.failure()
            GateOutcome.NOTIFIED, GateOutcome.DEDUPED, GateOutcome.DISABLED,
            GateOutcome.ACKED, GateOutcome.NO_DEVICE -> Result.success()
        }
    }

    companion object { const val KEY_EVENT_ID = "event_id" }
}
