package dk.foss.jarvis.hermes

import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.PendingModelIntent
import dk.foss.jarvis.net.E2eLog
import dk.foss.jarvis.ui.ModelOperationCoordinator

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
    intent: PendingModelIntent?,
): SessionTurnStartOutcome {
    val conversationId = repo.activeConversationId
    fun requireSameConversation() {
        if (repo.activeConversationId != conversationId) throw kotlinx.coroutines.CancellationException("Conversation changed")
    }
    val sid = repo.sessionId?.takeIf { it.isNotEmpty() }
        ?: createSessionForFirstTurn(client, title, uniqueSuffix).getOrElse {
        return SessionTurnStartOutcome.CreateFailed(it)
    }.also { created ->
        requireSameConversation()
        repo.bindSession(origin, created, ChatTransportKind.SESSIONS)
    }
    var runtime: RuntimeInfo? = null
    E2eLog.log("model intent=${intentLogName(intent)}")
    if (intent != null) {
        val operation = ModelOperationCoordinator.shared.next(conversationId, origin, sid)
        val result = ModelOperationCoordinator.shared.run(operation, {
            repo.activeConversationId == conversationId && repo.sessionId == sid && repo.origin == origin
        }) { when (intent) {
            is PendingModelIntent.Set -> client.setSessionModel(sid, intent.modelId)
            PendingModelIntent.Clear -> client.clearSessionModel(sid)
        } } ?: return SessionTurnStartOutcome.LockFailed(sid, kotlinx.coroutines.CancellationException("stale model operation"))
        requireSameConversation()
        if (result.isFailure) {
            val error = result.exceptionOrNull()!!
            val h = error as? HermesHttpError
            E2eLog.log("model intent=${intentLogName(intent)} ack=fail sid=$sid${h?.let { " code=${it.code}" } ?: ""}")
            return SessionTurnStartOutcome.LockFailed(sid, error)
        }
        runtime = result.getOrNull()?.runtime
        E2eLog.log("model intent=${intentLogName(intent)} ack=ok")
    }
    requireSameConversation()
    if (intent != null) repo.consumePendingModelIntentDurably(intent, conversationId)
    requireSameConversation()
    return SessionTurnStartOutcome.Started(sid, runtime)
}

private fun intentLogName(intent: PendingModelIntent?): String = when (intent) {
    null -> "none"
    is PendingModelIntent.Set -> "Set(model)"
    PendingModelIntent.Clear -> "Clear"
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
                caps, repo.transport, repo.origin, currentOrigin,
                hasMessages = repo.messages.any { !it.isError },
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
