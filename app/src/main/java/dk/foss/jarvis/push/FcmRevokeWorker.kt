package dk.foss.jarvis.push

import android.content.Context
import androidx.work.*
import dk.foss.jarvis.data.*
import dk.foss.jarvis.hermes.EventRpcClient
import dk.foss.jarvis.hermes.canonicalEndpointIdentity
import kotlinx.coroutines.flow.first
import java.time.Duration

class FcmRevokeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "hermes-fcm-revoke"
        internal val REVOKE_EXISTING_WORK_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

        /** Check if a URL string uses HTTP scheme. */
        private fun isHttpOrigin(url: String): Boolean = runCatching {
            val uri = java.net.URI.create(url.trim())
            uri.scheme?.lowercase() == "http"
        }.getOrDefault(false)

        /**
         * Derive the canonical cleanup key from a raw `hermesOrigin` string.
         *
         * Only HTTP origins are normalised; HTTPS returns `null`.  Malformed
         * URLs produce `null` without throwing — the caller must not attempt
         * cleanup when the result is `null`.
         *
         * This is the **only** place that derives the cleanup key, ensuring
         * the worker and tests use the identical normalisation.
         */
        @JvmStatic
        internal fun canonicalHttpCleanupOrigin(raw: String): String? = runCatching {
            if (raw.isNotBlank() && isHttpOrigin(raw)) {
                canonicalEndpointIdentity(raw)
            } else null
        }.getOrNull()

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

        val settingsStore = SettingsStore(app)
        val registry = DeviceRegistryStore(app)
        val secure = SecureStore.get(app)
        val settings = settingsStore.settings.first()

        when (val state = registry.loadOrMigrate(settings)) {
            RegistryState.Empty -> {
                FcmRevokeCleanup.onComplete(registry, secure, prefs, settingsStore) {
                    FcmTokenRegistration.enqueueCurrent(app)
                    prefs.setRegistrationState(FcmRegistrationState.REGISTERING)
                }
                Result.success()
            }
            is RegistryState.Registered -> {
                val r = state.registration
                // Capture the old origin BEFORE the revoke call so we can
                // remove its HTTP approval after successful revoke.
                // Only clear HTTP approval — HTTPS needs none.
                val oldOriginHttp = canonicalHttpCleanupOrigin(r.hermesOrigin)
                val revokeResult = EventRpcClient(r.hermesOrigin, r.apiKey, r.deviceId, r.deviceSecret).revoke()
                val error = revokeResult.exceptionOrNull()
                // When the old origin was manually revoked before the worker ran,
                // the gate blocks the revoke call with a BlockedRequest (wrapped
                // inside EventFetchException). The old device is effectively gone
                // — treat as success so cleanup completes without infinite retry.
                val wasBlocked = error is dk.foss.jarvis.net.BlockedRequest ||
                    (error is dk.foss.jarvis.hermes.EventFetchException &&
                        error.cause is dk.foss.jarvis.net.BlockedRequest)
                val o = FcmRevokePolicy.classify(if (wasBlocked) null else error)
                when (o) {
                    FcmRevokePolicy.RevokeOutcome.RevokeSuccess,
                    FcmRevokePolicy.RevokeOutcome.CredentialRejected,
                    -> {
                        FcmRevokeCleanup.onComplete(registry, secure, prefs, settingsStore, oldOriginHttp) {
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