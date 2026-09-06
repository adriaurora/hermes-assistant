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
import dk.foss.jarvis.hermes.EventClient
import dk.foss.jarvis.hermes.EventRpcClient
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
         * worker re-reads [PushPrefs.isPendingRevoke] and resumes where it
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
            val registration = registry.load() ?: run {
                // No registration found — nothing to revoke (already
                // deleted by a prior successful call or process death).
                // Clear pending so it does not terminally block future
                // registration, then mirror the idempotent-success path
                // above: if enabled, re-register a fresh token.
                prefs.setPendingRevoke(false)
                if (prefs.isEnabled()) {
                    FcmTokenRegistration.enqueueCurrent(app)
                    prefs.setRegistrationState(FcmRegistrationState.REGISTERING)
                } else {
                    prefs.setRegistrationState(FcmRegistrationState.DISABLED)
                }
                return@withLockReturning Result.success()
            }

            // The registration is bound to the origin and credential that created
            // it. This remains valid while SettingsStore is being changed.
            if (prefs.protocol() == PushProtocol.V1) {
                if (registration.deviceSecret.isNullOrBlank()) {
                    // device_secret absent — cannot authenticate revoke; clear
                    // and re-register clean (orphan device will send NO_DEVICE pushes).
                    registry.clear()
                    prefs.setPendingRevoke(false)
                    if (prefs.isEnabled()) {
                        FcmTokenRegistration.enqueueCurrent(app)
                        prefs.setRegistrationState(FcmRegistrationState.REGISTERING)
                    } else {
                        prefs.setRegistrationState(FcmRegistrationState.DISABLED)
                    }
                    return@withLockReturning Result.success()
                }
                when (RevokeV1Policy.classify(EventRpcClient(registration.hermesOrigin, registration.apiKey, registration.deviceId, registration.deviceSecret).revoke().exceptionOrNull())) {
                    RevokeAction.ConfirmAndClear -> { registry.clear(); prefs.setPendingRevoke(false); if (prefs.isEnabled()) { FcmTokenRegistration.enqueueCurrent(app); prefs.setRegistrationState(FcmRegistrationState.REGISTERING) } else prefs.setRegistrationState(FcmRegistrationState.DISABLED); return@withLockReturning Result.success() }
                    RevokeAction.KeepAndError -> { prefs.setRegistrationState(FcmRegistrationState.ERROR); return@withLockReturning Result.failure() }
                    RevokeAction.Retry -> { if (runAttemptCount < 5) { prefs.setRegistrationState(FcmRegistrationState.UNREGISTERING); return@withLockReturning Result.retry() } else { prefs.setRegistrationState(FcmRegistrationState.UNREGISTERING); return@withLockReturning Result.failure() } }
                }
            }
            val client = EventClient(registration.hermesOrigin, registration.apiKey, registration.deviceId)
            val result = client.revokeDevice()

            // Classify 404 as idempotent success; all other errors keep retrying.
            val outcome = FcmRevokePolicy.classify(result.exceptionOrNull())
            when (outcome) {
                FcmRevokePolicy.RevokeOutcome.RevokeSuccess -> {
                    // 404 or no error → idempotent cleanup.
                    registry.clear()
                    prefs.setPendingRevoke(false)
                    if (prefs.isEnabled()) {
                        // Enabled again (e.g. user re-enabled while revoke
                        // was in flight): re-register fresh token.
                        FcmTokenRegistration.enqueueCurrent(app)
                        prefs.setRegistrationState(FcmRegistrationState.REGISTERING)
                    } else {
                        prefs.setRegistrationState(FcmRegistrationState.DISABLED)
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
