package dk.foss.jarvis.push

/**
 * Pure policy for FCM revoke outcomes. 404 is idempotent success; all other
 * errors keep the worker alive via exponential back-off.
 *
 * This policy is stateless and testable without Android.
 */
object FcmRevokePolicy {
    private const val MAX_RETRIES = 5

    /**
     * Outcome after a single revoke attempt.
     *
     * [RevokeSuccess] — 404 or no error: device already gone or remote DELETE
     * worked.  The caller must clear registry and pending flag.
     * [RetryAgain]    — transient error, keep trying.
     * [RetryLater]    — max local retries exceeded; mark ERROR but keep
     *                   pending so WorkManager can schedule a future attempt
     *                   (long-lived back-off, not terminal failure).
     */
    sealed interface RevokeOutcome {
        object RevokeSuccess : RevokeOutcome
        object RetryAgain : RevokeOutcome
        object RetryLater : RevokeOutcome
    }

    /**
     * Classify a revoke result.  404 → success (idempotent cleanup).
     * All other errors → keep retrying (never terminal).
     */
    fun classify(error: Throwable?): RevokeOutcome = when {
        error == null -> RevokeOutcome.RevokeSuccess
        error.message?.contains("HTTP 404") == true -> RevokeOutcome.RevokeSuccess
        else -> RevokeOutcome.RetryAgain
    }

    /** Returns true while [attempt] < [maxRetries] — caller uses this to decide
     * Result.retry() vs Result.failure() when [classify] returns RetryAgain. */
    fun shouldRetryLocally(error: Throwable, attempt: Int, maxRetries: Int): Boolean =
        classify(error) == RevokeOutcome.RetryAgain && attempt < maxRetries
}
