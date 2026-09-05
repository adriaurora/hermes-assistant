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
        val registered = FcmPushRegistrar.registerCurrentToken(app)

        return if (registered) {
            // HTTP call succeeded; state is already set to ENABLED inside the lock.
            Result.success()
        } else {
            // registerCurrentToken returned false — either HTTP failure or state
            // mismatch inside the lock (e.g. disable was called).  Recheck: if
            // disabled/revoking, skip (no retry needed); otherwise retry HTTP.
            val recheckPrefs = PushPrefs(app)
            if (recheckPrefs.isPendingRevoke() || !recheckPrefs.isEnabled()) {
                // Skipped — state changed by disable.  No retry needed.
                Result.success()
            } else {
                // HTTP call failed.
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
        }
    }
}
