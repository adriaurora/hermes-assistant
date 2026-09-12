package dk.foss.jarvis.data

import android.content.Context

data class DeviceRegistration(
    val deviceId: String,
    val pushEndpoint: String,
    val hermesOrigin: String,
    val apiKey: String,
    val deviceSecret: String? = null,
) {
    override fun toString(): String =
        "DeviceRegistration(deviceId=$deviceId, pushEndpoint=$pushEndpoint, hermesOrigin=$hermesOrigin, apiKey=REDACTED, deviceSecret=REDACTED)"
}

/** Keystore-backed local identity and pinned push credentials. */
sealed interface RegistryState {
    data class Registered(val registration: DeviceRegistration) : RegistryState
    object Empty : RegistryState
}

class DeviceRegistryStore internal constructor(private val secure: SecureStore) {
    constructor(context: Context) : this(SecureStore.get(context))

    fun load(): DeviceRegistration? {
        val id = secure.loadDeviceId() ?: return null
        val endpoint = secure.loadPushEndpoint() ?: return null
        val origin = secure.loadPushOrigin() ?: return null
        val apiKey = secure.loadPushApiKey() ?: return null
        return DeviceRegistration(id, endpoint, origin, apiKey, secure.loadDeviceSecret())
    }

    fun loadOrMigrate(settings: JarvisSettings): RegistryState {
        val id = secure.loadDeviceId() ?: return RegistryState.Empty
        val endpoint = secure.loadPushEndpoint() ?: return RegistryState.Empty
        val origin = secure.loadPushOrigin()
        val apiKey = secure.loadPushApiKey()
        if (origin != null && apiKey != null) return RegistryState.Registered(DeviceRegistration(id, endpoint, origin, apiKey, secure.loadDeviceSecret()))
        if (settings.isConfigured) {
            val boundOrigin = origin ?: settings.baseUrl.trim().trimEnd('/')
            if (origin == null) secure.savePushOrigin(boundOrigin)
            if (apiKey == null) secure.savePushApiKey(settings.apiKey)
            return RegistryState.Registered(DeviceRegistration(id, endpoint, boundOrigin, apiKey ?: settings.apiKey, secure.loadDeviceSecret()))
        }
        return RegistryState.Empty
    }

    fun saveV1(deviceId: String, deviceSecret: String, pushEndpoint: String, hermesOrigin: String, apiKey: String) {
        secure.savePushEndpoint(pushEndpoint)
        secure.savePushOrigin(hermesOrigin)
        secure.savePushApiKey(apiKey)
        secure.saveDeviceSecret(deviceSecret)
        secure.saveDeviceId(deviceId)

    }

    fun save(deviceId: String, pushEndpoint: String, hermesOrigin: String = "", apiKey: String = "") {
        secure.saveDeviceId(deviceId); secure.savePushEndpoint(pushEndpoint)
        if (hermesOrigin.isNotEmpty()) secure.savePushOrigin(hermesOrigin)
        if (apiKey.isNotEmpty()) secure.savePushApiKey(apiKey)
    }
    fun updateCredentials(apiKey: String) {
        if (apiKey.isNotEmpty()) secure.savePushApiKey(apiKey)
    }

    fun clear() {
        secure.clearDeviceId()
        secure.clearPushEndpoint()
        secure.clearPushOrigin()
        secure.clearPushApiKey()
        secure.clearDeviceSecret()
    }
}