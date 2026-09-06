package dk.foss.jarvis.push

import dk.foss.jarvis.hermes.HermesHttpException

/**
 * Pure policy for FCM revoke outcomes. 404 is idempotent success; 401/403
 * abandon the attempt (credential rejected); every other error keeps the
 * worker alive via exponential back-off.
 *
 * This policy is stateless and testable without Android.
 */
object FcmRevokePolicy {

    /**
     * Outcome after a single revoke attempt.
     *
      * [RevokeSuccess] — 404 or no error: device already gone or remote DELETE
      * worked.  The caller must clear registry and pending flag.
      * [CredentialRejected] — 401/403: the server rejects the pinned credential;
      * retrying can never succeed. The caller purges local copies and unblocks.
      * [RetryAgain]    — transient error, keep trying.
     */
    sealed interface RevokeOutcome {
        object RevokeSuccess : RevokeOutcome
        object CredentialRejected : RevokeOutcome
        object RetryAgain : RevokeOutcome
    }

    /**
     * Classify a revoke result. 404 → success (idempotent cleanup).
     * 401/403 → CredentialRejected (abandon). All other errors → keep retrying.
     */
    fun classify(error: Throwable?): RevokeOutcome = when {
        error == null -> RevokeOutcome.RevokeSuccess
        error is HermesHttpException && error.statusCode == 404 -> RevokeOutcome.RevokeSuccess
        error is HermesHttpException && (error.statusCode == 401 || error.statusCode == 403) -> RevokeOutcome.CredentialRejected
        else -> RevokeOutcome.RetryAgain
    }
}
