package dk.foss.jarvis.push

enum class TokenSyncOutcome { REGISTERED, UPDATED, DISABLED, RETRYABLE, PERMANENT }
sealed interface EnrollmentAction {
    data object None : EnrollmentAction
    data object RegisterFresh : EnrollmentAction
    data object UpdateToken : EnrollmentAction
}
object EnrollmentPolicy {
    fun decide(hasRegistration: Boolean, hasSecret: Boolean): EnrollmentAction = when {
        !hasRegistration -> EnrollmentAction.RegisterFresh
        hasSecret -> EnrollmentAction.UpdateToken
        else -> EnrollmentAction.None
    }
}