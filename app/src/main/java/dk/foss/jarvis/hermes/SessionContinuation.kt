package dk.foss.jarvis.hermes

import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.net.E2eLog

sealed class SessionTurnStartOutcome {
    data class Started(val sessionId: String, val runtime: RuntimeInfo? = null) : SessionTurnStartOutcome()
    data class LockFailed(val sessionId: String, val error: Throwable) : SessionTurnStartOutcome()
    data class CreateFailed(val error: Throwable) : SessionTurnStartOutcome()
}

/** Creates/binds a session and, when requested, obtains the model-lock ACK.
 * No chat turn is sent here: callers may only proceed after Started. */
suspend fun startSessionTurn(
    repo: ConversationRepository,
    client: HermesClient,
    origin: String,
    title: String,
    uniqueSuffix: String,
    option: String?,
): SessionTurnStartOutcome {
    val sid = repo.sessionId ?: createSessionForFirstTurn(client, title, uniqueSuffix).getOrElse {
        return SessionTurnStartOutcome.CreateFailed(it)
    }.also { created -> repo.bindSession(origin, created, ChatTransportKind.SESSIONS) }
    var runtime: RuntimeInfo? = null
    if (option != null) {
        val result = client.setSessionModel(sid, option)
        if (result.isFailure) {
            val error = result.exceptionOrNull()!!
            val h = error as? HermesHttpError
            E2eLog.log("model lock failed sid=$sid${h?.let { " code=${it.code}" } ?: ""}")
            return SessionTurnStartOutcome.LockFailed(sid, error)
        }
        runtime = result.getOrNull()?.runtime
    }
    return SessionTurnStartOutcome.Started(sid, runtime)
}


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
