package dk.foss.jarvis.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dk.foss.jarvis.data.DeviceRegistryStore
import dk.foss.jarvis.data.RegistryState
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.data.SecureStore
import dk.foss.jarvis.hermes.EventClient
import kotlinx.coroutines.flow.first
import java.time.Duration

/** WorkManager worker that revokes the device registration on the server. */
class FcmRevokeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "hermes-fcm-revoke"

        /**
         * Schedule (or replace) the revoke work. Uses network-constrained
         * exponential-backoff via WorkManager's built-in retry.
         *
         * WorkManager persists and reschedules eligible work across reboot
         * on Android 10+ (API 29+, the app's minSdk).  After reboot the
         * worker re-reads [PushPrefs.isPendingRevoke] and credential-clear state and resumes where it
         * left off.
         *
         * NOTE: FcmLifecycle.withLock serializes this worker's state check,
         * HTTP call, and cleanup against enable/disable and the registration
         * worker.  This is an in-process mutex — process death loses it.  The
         * PushPrefs state machine self-heals on restart because every
         * operation re-reads state inside the lock before acting.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<FcmRevokeWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMillis(30_000L))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }

    override suspend fun doWork(): Result {
        // Whole state/check/network/cleanup transaction under the lock.
        return FcmLifecycle.withLockReturning {
            val app = applicationContext
            val prefs = PushPrefs(app)

            // Guard: only run if a revoke is pending.
            if (!prefs.isPendingRevoke()) return@withLockReturning Result.success()

            val registry = DeviceRegistryStore(app)
            when (val state = registry.loadOrMigrate(SettingsStore(app).settings.first())) {
                RegistryState.Empty -> {
                    prefs.setPendingRevoke(false)
                    if (prefs.isEnabled()) { FcmTokenRegistration.enqueueCurrent(app); prefs.setRegistrationState(FcmRegistrationState.REGISTERING) }
                    else prefs.setRegistrationState(FcmRegistrationState.DISABLED)
                    Result.success()
                }
                is RegistryState.LegacyPending -> {
                    if (prefs.isPendingCredentialClear()) { registry.clear(); prefs.setPendingCredentialClear(false) }
                    prefs.setPendingRevoke(false)
                    if (prefs.isEnabled()) { FcmTokenRegistration.enqueueCurrent(app); prefs.setRegistrationState(FcmRegistrationState.REGISTERING) }
                    else prefs.setRegistrationState(FcmRegistrationState.DISABLED)
                    Result.success()
                }
                is RegistryState.Registered -> {
                    val r = state.registration
                    val outcome = FcmRevokePolicy.classify(EventClient(r.hermesOrigin, r.apiKey, r.deviceId).revokeDevice().exceptionOrNull())
                    when (outcome) {
                        FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.RevokeOutcome.CredentialRejected -> {
                            FcmRevokeCleanup.onComplete(registry, SecureStore.get(app), prefs) {
                                FcmTokenRegistration.enqueueCurrent(app); prefs.setRegistrationState(FcmRegistrationState.REGISTERING)
                            }
                            Result.success()
                        }
                        FcmRevokePolicy.RevokeOutcome.RetryAgain -> { prefs.setRegistrationState(FcmRegistrationState.UNREGISTERING); Result.retry() }
                    }
                }
            }
        }
    }
}
