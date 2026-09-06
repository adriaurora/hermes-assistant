package dk.foss.jarvis.push

/**
 * Pure policy for FCM registration decisions.
 *
 * Registration is allowed only when:
 * - Push is enabled
 * - Token is present and non-blank
 * - No revoke is pending (we are not in the process of unregistering)
 * - No credential clear is pending
 *
 * This policy is stateless and testable without Android.
 */
object FcmRegistrationPolicy {
    fun shouldRegister(enabled: Boolean, token: String?, pendingRevoke: Boolean = false, pendingCredentialClear: Boolean = false): Boolean =
        enabled && !token.isNullOrBlank() && !pendingRevoke && !pendingCredentialClear
}
