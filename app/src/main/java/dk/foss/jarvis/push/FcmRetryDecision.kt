package dk.foss.jarvis.push

import dk.foss.jarvis.events.RetryPolicy

object FcmRetryDecision {
    fun shouldRetry(outcome: GateOutcome, attempt: Int): Boolean =
        outcome == GateOutcome.FETCH_FAILURE && attempt < RetryPolicy.MAX_RETRIES
}
