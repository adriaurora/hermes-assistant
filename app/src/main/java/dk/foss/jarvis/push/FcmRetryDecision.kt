package dk.foss.jarvis.push

import dk.foss.jarvis.events.RetryPolicy

object FcmRetryDecision {
    fun shouldRetry(outcome: GateOutcome, attempt: Int): Boolean =
        outcome in setOf(GateOutcome.FETCH_FAILURE, GateOutcome.DELIVERY_FAILURE, GateOutcome.ACK_FAILURE) &&
            attempt < RetryPolicy.MAX_RETRIES
}
