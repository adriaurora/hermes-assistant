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
    fun decide(transport: PushTransport, hasRegistration: Boolean, hasSecret: Boolean): EnrollmentAction = when {
        transport == PushTransport.LEGACY -> EnrollmentAction.None
        !hasRegistration -> EnrollmentAction.RegisterFresh(false)
        !hasSecret -> EnrollmentAction.RegisterFresh(true)
        else -> EnrollmentAction.UpdateToken
    }
}
sealed interface RevokeAction { data object ConfirmAndClear : RevokeAction; data object KeepAndError : RevokeAction; data object Retry : RevokeAction }
object RevokeV1Policy {
    fun classify(error: Throwable?): RevokeAction = when {
        error == null -> RevokeAction.ConfirmAndClear
        (error as? EventFetchException)?.rpcCode == "device_not_found" -> RevokeAction.ConfirmAndClear
        (error as? EventFetchException)?.rpcCode == "device_revoked" -> RevokeAction.ConfirmAndClear
        (error as? EventFetchException)?.rpcCode == "device_auth_failed" -> RevokeAction.KeepAndError
        error is EventFetchException && error.rpcCode != null -> RevokeAction.KeepAndError
        error is EventFetchException && error.kind == FetchFailureKind.OTHER -> RevokeAction.KeepAndError
        else -> RevokeAction.Retry
    }
}
