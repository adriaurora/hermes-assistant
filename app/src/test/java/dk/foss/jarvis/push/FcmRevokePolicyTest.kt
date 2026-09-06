package dk.foss.jarvis.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure policy tests for FcmRevokePolicy — no Android dependencies.
 *
 * Key contracts:
 * 1. HTTP 404 → RevokeSuccess (idempotent cleanup, no retry).
 * 2. All other errors → RetryAgain (never terminal).
     * 3. All non-404 failures remain retryable.
 */
class FcmRevokePolicyTest {

    // ── classify ──────────────────────────────────────────────────────────

    @Test fun `404 is idempotent success`() {
        val err = dk.foss.jarvis.hermes.HermesHttpException(404)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.classify(err))
    }

    @Test fun `404 no error also success`() {
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.classify(null))
    }

    @Test fun `null error is success`() {
        // When error is null (not an Exception but Result.Success path),
        // classify returns RevokeSuccess.
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.classify(null))
    }

    @Test fun `network errors classify as RetryAgain`() {
        val io = java.net.ConnectException("timeout")
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(io))
    }

    @Test fun `HTTP 500 classify as RetryAgain`() {
        val err = dk.foss.jarvis.hermes.HermesHttpException(500)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(err))
    }

    @Test fun `HTTP 429 classify as RetryAgain`() {
        val err = dk.foss.jarvis.hermes.HermesHttpException(429)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(err))
    }

    @Test fun `null message classifies as RetryAgain`() {
        // A throwable with no message is NOT a 404, so it retries.
        val ex = java.lang.Exception()
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(ex))
    }

    @Test fun `typed 404 only is idempotent`() {
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain,
            FcmRevokePolicy.classify(Exception("HTTP 404: not found")))
    }
}
