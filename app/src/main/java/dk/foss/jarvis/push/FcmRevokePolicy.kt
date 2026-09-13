package dk.foss.jarvis.push

import dk.foss.jarvis.hermes.EventFetchException
import dk.foss.jarvis.hermes.HermesHttpException

/**
 * Pure policy for FCM revoke outcomes.
 *
 * Classification matrix (applied in order):
 * 1. null → RevokeSuccess (no error)
 * 2. EventFetchException.rpcCode == "device_not_found" / "device_revoked" → RevokeSuccess
 * 3. EventFetchException.rpcCode == "device_auth_failed" → CredentialRejected
 * 4. EventFetchException.statusCode 404 → RevokeSuccess (idempotent)
 * 5. EventFetchException.statusCode 401/403 → CredentialRejected
 * 6. HermesHttpException 404 → RevokeSuccess (legacy compat; rare but possible)
 * 7. HermesHttpException 401/403 → CredentialRejected
 * 8. Everything else → RetryAgain (transient)
 */
object FcmRevokePolicy {

    sealed interface RevokeOutcome {
        object RevokeSuccess : RevokeOutcome
        object CredentialRejected : RevokeOutcome
        object RetryAgain : RevokeOutcome
    }

    fun classify(error: Throwable?): RevokeOutcome = when {
        error == null -> RevokeOutcome.RevokeSuccess

        error is EventFetchException -> classifyEventFetch(error)

        error is HermesHttpException -> classifyHttp(error.statusCode)

        else -> RevokeOutcome.RetryAgain
    }

    private fun classifyEventFetch(e: EventFetchException): RevokeOutcome {
        val code = e.rpcCode
        if (code == "device_not_found" || code == "device_revoked") return RevokeOutcome.RevokeSuccess
        if (code == "device_auth_failed") return RevokeOutcome.CredentialRejected
        return e.statusCode?.let { classifyHttp(it) }
            ?: RevokeOutcome.RetryAgain
    }

    private fun classifyHttp(statusCode: Int): RevokeOutcome = when (statusCode) {
        404 -> RevokeOutcome.RevokeSuccess
        401, 403 -> RevokeOutcome.CredentialRejected
        else -> RevokeOutcome.RetryAgain
    }
}
