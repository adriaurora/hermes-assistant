package dk.foss.jarvis.push
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
class FcmTokenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app=applicationContext; val prefs=PushPrefs(app)
        if (!LifecycleGuards.canAcceptTokenSync(prefs.isEnabled(), prefs.isPendingRevoke(), prefs.isPendingCredentialClear())) return Result.success()
        return when(FcmPushRegistrar.registerCurrentTokenOutcome(app)) {
            TokenSyncOutcome.REGISTERED, TokenSyncOutcome.UPDATED, TokenSyncOutcome.DISABLED -> Result.success()
            TokenSyncOutcome.RETRYABLE -> { val r=PushPrefs(app); if(r.isPendingRevoke()||r.isPendingCredentialClear()||!r.isEnabled()) Result.success() else if(runAttemptCount<2) Result.retry() else Result.failure() }
            TokenSyncOutcome.PERMANENT -> { val r=PushPrefs(app); if(r.isPendingRevoke()||r.isPendingCredentialClear()||!r.isEnabled()) Result.success() else Result.failure() }
        }
    }
}
