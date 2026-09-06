package dk.foss.jarvis.data

import dk.foss.jarvis.hermes.EventClient

/** Pure decision for changing the configured Hermes connection. */
data class ConnectionTransition(
    val revokeRequired: Boolean,
    val credentialClear: Boolean,
    val preservedRegistration: DeviceRegistration?,
) {
    companion object {
        fun decide(old: JarvisSettings, new: JarvisSettings, registration: DeviceRegistration?): ConnectionTransition =
            ConnectionTransition(
                revokeRequired = registration != null &&
                    EventClient.originIdentity(old.baseUrl) != EventClient.originIdentity(new.baseUrl),
                credentialClear = old.apiKey.isNotEmpty() && new.apiKey.isEmpty(),
                preservedRegistration = registration,
            )
    }
}
