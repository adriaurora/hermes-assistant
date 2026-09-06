package dk.foss.jarvis.push

import dk.foss.jarvis.hermes.HermesHttpException

/**
 * Pure policy for FCM revoke outcomes. 404 is idempotent success; all other
 * errors keep the worker alive via exponential back-off.
 *
 * This policy is stateless and testable without Android.
 */
object FcmRevokePolicy {

    /**
     * Outcome after a single revoke attempt.
     *
     * [RevokeSuccess] — 404 or no error: device already gone or remote DELETE
     * worked.  The caller must clear registry and pending flag.
     * [RetryAgain]    — transient error, keep trying.
     */
    sealed interface RevokeOutcome {
        object RevokeSuccess : RevokeOutcome
        object RetryAgain : RevokeOutcome
    }

    /**
     * Classify a revoke result.  404 → success (idempotent cleanup).
     * All other errors → keep retrying (never terminal).
     */
    fun classify(error: Throwable?): RevokeOutcome = when {
        error == null -> RevokeOutcome.RevokeSuccess
        error is HermesHttpException && error.statusCode == 404 -> RevokeOutcome.RevokeSuccess
        else -> RevokeOutcome.RetryAgain
    }
}
