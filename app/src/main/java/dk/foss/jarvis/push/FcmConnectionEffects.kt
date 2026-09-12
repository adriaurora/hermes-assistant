package dk.foss.jarvis.push

import dk.foss.jarvis.data.ConnectionTransition
import dk.foss.jarvis.data.DeviceRegistryStore
import dk.foss.jarvis.data.JarvisSettings
import dk.foss.jarvis.data.RegistryState

/** Applies push-lifecycle side effects to a connection change (settings save, bearer clear, A→B switch). The caller runs it under FcmLifecycle.withLock so it is serialized against the registration and revoke workers. */
class FcmConnectionEffects(private val pushState: PushState, private val registry: DeviceRegistryStore, private val onScheduleRevoke: () -> Unit, private val onCancelRegistrationWork: () -> Unit) {
    /** Bindings and revokes always use the OLD settings, so a record stays bound to the origin that created it and A→B revokes against A. */
    suspend fun onConnectionChanged(old: JarvisSettings, new: JarvisSettings) {
        val state = registry.loadOrMigrate(old)
        val existing = (state as? RegistryState.Registered)?.registration
        val transition = ConnectionTransition.decide(old, new, existing)
        if (new.apiKey.isNotEmpty()) pushState.setPendingCredentialClear(false)
        if (transition.credentialClear) {
            if (existing != null) { pushState.disable(); pushState.setPendingRevoke(true); pushState.setPendingCredentialClear(true); pushState.setRegistrationState(FcmRegistrationState.UNREGISTERING); onScheduleRevoke(); onCancelRegistrationWork() }
            else { registry.clear(); pushState.setPendingRevoke(false); pushState.setPendingCredentialClear(false); pushState.setRegistrationState(FcmRegistrationState.DISABLED) }
            return
        }
        existing ?: return
        if (transition.revokeRequired) { pushState.setPendingRevoke(true); pushState.setRegistrationState(FcmRegistrationState.UNREGISTERING); onScheduleRevoke(); onCancelRegistrationWork() }
        else if (!pushState.isPendingRevoke() && old.apiKey != new.apiKey) registry.updateCredentials(new.apiKey)
    }
}