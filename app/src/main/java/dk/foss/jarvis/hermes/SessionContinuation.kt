package dk.foss.jarvis.hermes

import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.net.E2eLog

/** Result of the repository/transport boundary check preceding a chat turn. */
sealed class ContinuationPlan {
    data class Send(val decision: ChatTransportDecision) : ContinuationPlan()
    data class Blocked(val outcome: ConversationRepository.RebindOutcome) : ContinuationPlan()
}

/** Verify session continuity before looking at origin compatibility or sending a turn. */
suspend fun resolveContinuation(
    repo: ConversationRepository,
    client: HermesClient,
    baseUrl: String,
    apiKey: String,
    hasMessages: Boolean,
): ContinuationPlan {
    return when (val gate = repo.verifySessionForCurrentOrigin(client, baseUrl, apiKey)) {
        is ConversationRepository.RebindOutcome.BlockedAuth,
        is ConversationRepository.RebindOutcome.BlockedMissing,
        is ConversationRepository.RebindOutcome.BlockedRetryable -> {
            E2eLog.log("continuation caps=UNKNOWN gate=${gateName(gate)} decision=Blocked")
            ContinuationPlan.Blocked(gate)
        }
        else -> {
            val currentOrigin = originIdentity(baseUrl, apiKey)
            val caps = CapabilityRegistry.capabilities(currentOrigin) { client.getCapabilities() }
            val decision = ChatTransportSelector.decide(
                caps, repo.transport, repo.origin, currentOrigin, hasMessages,
            )
            E2eLog.log("continuation caps=${caps.state} gate=${gateName(gate)} decision=${decision::class.simpleName}")
            ContinuationPlan.Send(decision)
        }
    }
}

private fun gateName(gate: ConversationRepository.RebindOutcome): String = when (gate) {
    ConversationRepository.RebindOutcome.VerifiedRebound -> "VerifiedRebound"
    ConversationRepository.RebindOutcome.NotNeeded -> "NotNeeded"
    is ConversationRepository.RebindOutcome.BlockedAuth -> "BlockedAuth"
    is ConversationRepository.RebindOutcome.BlockedMissing -> "BlockedMissing"
    is ConversationRepository.RebindOutcome.BlockedRetryable -> "BlockedRetryable"
}
