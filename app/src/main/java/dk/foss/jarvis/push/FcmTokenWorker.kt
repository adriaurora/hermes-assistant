package dk.foss.jarvis.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class FcmTokenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!PushPrefs(applicationContext).isEnabled()) return Result.success()
        val prefs = PushPrefs(applicationContext)
        prefs.setRegistrationState(FcmRegistrationState.REGISTERING)
        val token = FcmTokenRegistration.token(inputData)
        val registered = if (token != null) FcmPushRegistrar.registerToken(applicationContext, token)
        else FcmPushRegistrar.registerCurrentToken(applicationContext)
        return if (registered) {
            prefs.setRegistrationState(FcmRegistrationState.ENABLED)
            Result.success()
        } else {
            prefs.setRegistrationState(FcmRegistrationState.ERROR)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
