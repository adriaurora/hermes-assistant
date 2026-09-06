package dk.foss.jarvis.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmRetryDecisionTest {
    @Test fun onlyFetchFailuresRetryWithinBound() {
        assertTrue(FcmRetryDecision.shouldRetry(GateOutcome.FETCH_FAILURE, 0))
        assertFalse(FcmRetryDecision.shouldRetry(GateOutcome.FETCH_FAILURE, 2))
        assertFalse(FcmRetryDecision.shouldRetry(GateOutcome.DEDUPED, 0))
        assertFalse(FcmRetryDecision.shouldRetry(GateOutcome.DISABLED, 0))
    }

    @Test fun `permanent fetch does not retry`() {
        assertFalse(FcmRetryDecision.shouldRetry(GateOutcome.FETCH_PERMANENT, 0))
        assertFalse(FcmRetryDecision.shouldRetry(GateOutcome.FETCH_PERMANENT, 5))
    }
}
