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
 * 3. shouldRetryLocally only returns true for RetryAgain within limit.
 */
class FcmRevokePolicyTest {

    // ── classify ──────────────────────────────────────────────────────────

    @Test fun `404 is idempotent success`() {
        val err = Exception("HTTP 404: Device not found")
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
        val err = Exception("HTTP 500: Internal Server Error")
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(err))
    }

    @Test fun `HTTP 429 classify as RetryAgain`() {
        val err = Exception("HTTP 429: Too Many Requests")
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(err))
    }

    @Test fun `null message classifies as RetryAgain`() {
        // A throwable with no message is NOT a 404, so it retries.
        val ex = java.lang.Exception()
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(ex))
    }

    // ── shouldRetryLocally ────────────────────────────────────────────────

    @Test fun `shouldRetryLocally true within limit`() {
        val io = java.net.ConnectException("timeout")
        assertTrue(FcmRevokePolicy.shouldRetryLocally(io, 0, 5))
        assertTrue(FcmRevokePolicy.shouldRetryLocally(io, 4, 5))
    }

    @Test fun `shouldRetryLocally false at max retries`() {
        val io = java.net.ConnectException("timeout")
        assertFalse(FcmRevokePolicy.shouldRetryLocally(io, 5, 5))
    }

    @Test fun `shouldRetryLocally false for 404 regardless of attempt`() {
        val err = Exception("HTTP 404: not found")
        // 404 is RevokeSuccess, not RetryAgain → shouldRetryLocally is always false.
        assertFalse(FcmRevokePolicy.shouldRetryLocally(err, 0, 5))
        assertFalse(FcmRevokePolicy.shouldRetryLocally(err, 3, 5))
        assertFalse(FcmRevokePolicy.shouldRetryLocally(err, 5, 5))
    }

    @Test fun `null message does not crash`() {
        val ex = java.lang.Exception()
        assertTrue(FcmRevokePolicy.shouldRetryLocally(ex, 0, 5))
    }
}
