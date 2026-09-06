package dk.foss.jarvis.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** WorkManager worker that registers (or updates) the current FCM token. */
class FcmTokenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext

        // Fast-path checks without lock (cheap).
        val prefs = PushPrefs(app)
        if (!prefs.isEnabled()) return Result.success()
        if (prefs.isPendingRevoke()) return Result.success()

        // Fetch token outside lock (async).  If registration is called while
        // enabled, registerCurrentToken rechecks inside its lock.
        return when (FcmPushRegistrar.registerCurrentTokenOutcome(app)) {
            TokenSyncOutcome.DISABLED, TokenSyncOutcome.REGISTERED, TokenSyncOutcome.UPDATED -> Result.success()
            TokenSyncOutcome.PERMANENT -> {
                val recheckPrefs = PushPrefs(app)
                if (recheckPrefs.isPendingRevoke() || !recheckPrefs.isEnabled()) {
                    Result.success()
                } else {
                    Result.failure()
                }
            }
            TokenSyncOutcome.RETRYABLE -> if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }
}