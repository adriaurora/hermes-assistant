package dk.foss.jarvis.push

/**
 * Centralised guards for FCM lifecycle operations.
 *
 * No registration may be enqueued while a revoke or credential clear is
 * pending — the revoke worker will re-register once the device is purged.
 *
 * These predicates are pure, stateless, and fully testable without Android.
 */
object LifecycleGuards {

    /** Returns true when a registration *may* be enqueued. */
    fun shouldEnqueueRegistration(enabled: Boolean, pendingRevoke: Boolean, pendingCredentialClear: Boolean): Boolean =
        enabled && !pendingRevoke && !pendingCredentialClear

    /** Returns true when a token sync *may* proceed. */
    fun canAcceptTokenSync(enabled: Boolean, pendingRevoke: Boolean, pendingCredentialClear: Boolean): Boolean =
        shouldEnqueueRegistration(enabled, pendingRevoke, pendingCredentialClear)
}