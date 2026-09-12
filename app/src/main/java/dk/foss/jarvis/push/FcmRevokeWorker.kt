package dk.foss.jarvis.push

import android.content.Context
import androidx.work.*
import dk.foss.jarvis.data.*
import dk.foss.jarvis.hermes.*
import kotlinx.coroutines.flow.first
import java.time.Duration

class FcmRevokeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "hermes-fcm-revoke"
        internal val REVOKE_EXISTING_WORK_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

        fun schedule(c: Context) {
            val request = OneTimeWorkRequestBuilder<FcmRevokeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork(WORK_NAME, REVOKE_EXISTING_WORK_POLICY, request)
        }
    }

    override suspend fun doWork(): Result = FcmLifecycle.withLockReturning {
        val app = applicationContext
        val prefs = PushPrefs(app)
        if (!prefs.isPendingRevoke()) return@withLockReturning Result.success()

        val registry = DeviceRegistryStore(app)
        val secure = SecureStore.get(app)

        when (val state = registry.loadOrMigrate(SettingsStore(app).settings.first())) {
            RegistryState.Empty -> {
                FcmRevokeCleanup.onComplete(registry, secure, prefs) {
                    FcmTokenRegistration.enqueueCurrent(app)
                    prefs.setRegistrationState(FcmRegistrationState.REGISTERING)
                }
                Result.success()
            }
            is RegistryState.Registered -> {
                val r = state.registration
                val o = FcmRevokePolicy.classify(
                    EventRpcClient(r.hermesOrigin, r.apiKey, r.deviceId, r.deviceSecret).revoke().exceptionOrNull()
                )
                when (o) {
                    FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.RevokeOutcome.CredentialRejected -> {
                        FcmRevokeCleanup.onComplete(registry, secure, prefs) {
                            FcmTokenRegistration.enqueueCurrent(app)
                            prefs.setRegistrationState(FcmRegistrationState.REGISTERING)
                        }
                        Result.success()
                    }
                    FcmRevokePolicy.RevokeOutcome.RetryAgain -> {
                        prefs.setRegistrationState(FcmRegistrationState.UNREGISTERING)
                        Result.retry()
                    }
                }
            }
        }
    }
}