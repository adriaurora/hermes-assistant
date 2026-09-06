package dk.foss.jarvis.data

import android.content.Context

data class DeviceRegistration(val deviceId: String, val pushEndpoint: String, val hermesOrigin: String, val apiKey: String)

/** Result of [DeviceRegistryStore.loadOrMigrate]. */
sealed interface RegistryState {
    data class Registered(val registration: DeviceRegistration) : RegistryState
    data class LegacyPending(val deviceId: String, val pushEndpoint: String) : RegistryState
    object Empty : RegistryState
}

/** Keystore-backed local identity and pinned push credentials. */
class DeviceRegistryStore internal constructor(private val secure: SecureStore) {
    constructor(context: Context) : this(SecureStore.get(context))

    fun loadOrMigrate(settings: JarvisSettings): RegistryState {
        val id = secure.loadDeviceId() ?: return RegistryState.Empty
        val endpoint = secure.loadPushEndpoint() ?: return RegistryState.Empty
        val origin = secure.loadPushOrigin()
        val apiKey = secure.loadPushApiKey()
        if (origin != null && apiKey != null) return RegistryState.Registered(DeviceRegistration(id, endpoint, origin, apiKey))
        if (settings.isConfigured) {
            val boundOrigin = origin ?: settings.baseUrl.trim().trimEnd('/')
            if (origin == null) secure.savePushOrigin(boundOrigin)
            if (apiKey == null) secure.savePushApiKey(settings.apiKey)
            return RegistryState.Registered(DeviceRegistration(id, endpoint, boundOrigin, apiKey ?: settings.apiKey))
        }
        return RegistryState.LegacyPending(id, endpoint)
    }

    fun save(deviceId: String, pushEndpoint: String, hermesOrigin: String = "", apiKey: String = "") {
        secure.saveDeviceId(deviceId); secure.savePushEndpoint(pushEndpoint)
        if (hermesOrigin.isNotEmpty()) secure.savePushOrigin(hermesOrigin)
        if (apiKey.isNotEmpty()) secure.savePushApiKey(apiKey)
    }
    fun updateCredentials(apiKey: String) { if (apiKey.isNotEmpty()) secure.savePushApiKey(apiKey) }
    fun clear() { secure.clearDeviceId(); secure.clearPushEndpoint(); secure.clearPushOrigin(); secure.clearPushApiKey() }
}
