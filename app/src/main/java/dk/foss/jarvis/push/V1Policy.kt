package dk.foss.jarvis.push

import dk.foss.jarvis.hermes.EventFetchException
import dk.foss.jarvis.hermes.FetchFailureKind

enum class TokenSyncOutcome { REGISTERED, UPDATED, DISABLED, RETRYABLE, PERMANENT }
sealed interface EnrollmentAction {
    data object None : EnrollmentAction
    data class RegisterFresh(val legacyRevokeFirst: Boolean) : EnrollmentAction
    data object UpdateToken : EnrollmentAction
}
object EnrollmentPolicy {
    fun decide(hasRegistration: Boolean, hasSecret: Boolean): EnrollmentAction = when {
        !hasRegistration -> EnrollmentAction.RegisterFresh(false)
        !hasSecret -> EnrollmentAction.RegisterFresh(true)
        else -> EnrollmentAction.UpdateToken
    }
}
object RevokeV1Policy {
    fun classify(error: Throwable?): FcmRevokePolicy.RevokeOutcome = when {
        error == null -> FcmRevokePolicy.RevokeOutcome.RevokeSuccess
        (error as? EventFetchException)?.rpcCode == "device_not_found" -> FcmRevokePolicy.RevokeOutcome.RevokeSuccess
        (error as? EventFetchException)?.rpcCode == "device_revoked" -> FcmRevokePolicy.RevokeOutcome.RevokeSuccess
        (error as? EventFetchException)?.rpcCode == "device_auth_failed" -> FcmRevokePolicy.RevokeOutcome.CredentialRejected
        error is EventFetchException && error.rpcCode != null -> FcmRevokePolicy.RevokeOutcome.RetryAgain
        error is EventFetchException && error.kind == FetchFailureKind.OTHER -> FcmRevokePolicy.RevokeOutcome.RetryAgain
        else -> FcmRevokePolicy.RevokeOutcome.RetryAgain
    }
}