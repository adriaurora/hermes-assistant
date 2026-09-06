package dk.foss.jarvis.push

import dk.foss.jarvis.data.DeviceRegistryStore
import dk.foss.jarvis.data.SecureStore

internal object FcmRevokeCleanup {
    suspend fun onComplete(registry: DeviceRegistryStore, secure: SecureStore, pushState: PushState, onReRegister: suspend () -> Unit) {
        val clearRequested = pushState.isPendingCredentialClear()
        registry.clear()
        if (clearRequested) { secure.clearToken(); pushState.setPendingCredentialClear(false) }
        pushState.setPendingRevoke(false)
        if (pushState.isEnabled()) onReRegister() else pushState.setRegistrationState(FcmRegistrationState.DISABLED)
    }
}
