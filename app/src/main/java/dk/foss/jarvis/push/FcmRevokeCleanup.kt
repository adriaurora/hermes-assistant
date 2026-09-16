package dk.foss.jarvis.push

import dk.foss.jarvis.data.DeviceRegistryStore
import dk.foss.jarvis.data.SecureStore
import dk.foss.jarvis.data.SettingsStore

/**
 * Cleanup logic that runs when an FCM revoke succeeds.
 *
 * ## Critical push lifecycle
 *
 * When settings change (endpoint, key, or disable), the old registration is
 * preserved and a revoke worker ([FcmRevokeWorker]) is scheduled.
 *
 * The old HTTP origin approval must remain active until the revoke worker
 * completes — either successfully (removing the approval) or failing/being
 * blocked (e.g. if the old origin was manually revoked by the user).
 *
 * When the user manually revokes an origin before the revoke worker runs,
 * [FcmRevokeWorker] detects the [dk.foss.jarvis.net.BlockedRequest] and
 * treats it as a successful revoke — the old device is effectively gone.
 *
 * ## Approval removal semantics
 *
 * The caller passes the old origin as a normalised string (the result of
 * [dk.foss.jarvis.hermes.canonicalEndpointIdentity] or `hermesOrigin` from the
 * registry).  [onComplete] removes it from the approved set after a successful
 * revoke.  If the old origin was HTTPS, nothing is removed — HTTPS is always
 * allowed and requires no approval.
 */
internal object FcmRevokeCleanup {

    /**
     * Called on successful revoke completion.
     *
     * @param oldOriginHttp the normalised HTTP origin to revoke approval for.
     *   May be null if the old registration was HTTPS-only (no approval needed).
     *   The caller resolves it from [RegistryState.Registered] before entering
     *   the lock and passes it in here.
     */
    suspend fun onComplete(
        registry: DeviceRegistryStore,
        secure: SecureStore,
        pushState: PushState,
        settingsStore: SettingsStore,
        oldOriginHttp: String? = null,
        onReRegister: suspend () -> Unit,
    ) {
        val clearRequested = pushState.isPendingCredentialClear()

        // Remove HTTP approval for the old origin (if any).
        oldOriginHttp?.let { origin ->
            settingsStore.revokeHttpOrigin(origin)
        }

        registry.clear()
        if (clearRequested) {
            secure.clearToken()
            pushState.setPendingCredentialClear(false)
        }
        pushState.setPendingRevoke(false)
        if (pushState.isEnabled()) onReRegister() else pushState.setRegistrationState(FcmRegistrationState.DISABLED)
    }
}