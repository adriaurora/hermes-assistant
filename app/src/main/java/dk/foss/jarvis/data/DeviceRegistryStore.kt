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
        if (secure.hasDeviceRegistrationMarker()) return secure.loadDeviceRegistration()
        val id = secure.loadDeviceId() ?: return null
        val endpoint = secure.loadPushEndpoint() ?: return null
        val origin = secure.loadPushOrigin() ?: return null
        val apiKey = secure.loadPushApiKey() ?: return null
        return DeviceRegistration(id, endpoint, origin, apiKey, secure.loadDeviceSecret()).also {
            secure.saveDeviceRegistration(it)
        }
    }

    fun loadOrMigrate(settings: JarvisSettings): RegistryState {
        if (secure.hasDeviceRegistrationMarker()) return secure.loadDeviceRegistration()?.let { RegistryState.Registered(it) } ?: RegistryState.Empty
        val id = secure.loadDeviceId() ?: return RegistryState.Empty
        val endpoint = secure.loadPushEndpoint() ?: return RegistryState.Empty
        val origin = secure.loadPushOrigin()
        val apiKey = secure.loadPushApiKey()
        if (origin != null && apiKey != null) return RegistryState.Registered(DeviceRegistration(id, endpoint, origin, apiKey, secure.loadDeviceSecret()).also {
            secure.saveDeviceRegistration(it)
        })
        if (settings.isConfigured) {
            val boundOrigin = origin ?: settings.baseUrl.trim().trimEnd('/')
            if (origin == null) secure.savePushOrigin(boundOrigin)
            if (apiKey == null) secure.savePushApiKey(settings.apiKey)
            return RegistryState.Registered(DeviceRegistration(id, endpoint, boundOrigin, apiKey ?: settings.apiKey, secure.loadDeviceSecret()).also {
                secure.saveDeviceRegistration(it)
            })
        }
        return RegistryState.Empty
    }

    fun saveV1(deviceId: String, deviceSecret: String, pushEndpoint: String, hermesOrigin: String, apiKey: String) {
        secure.saveDeviceRegistration(DeviceRegistration(deviceId, pushEndpoint, hermesOrigin, apiKey, deviceSecret))
    }

    fun save(deviceId: String, pushEndpoint: String, hermesOrigin: String = "", apiKey: String = "") {
        if (secure.hasDeviceRegistrationMarker()) {
            val old = secure.loadDeviceRegistration()
            if (old == null && secure.isDeviceRegistrationTombstone()) {
                if (hermesOrigin.isNotEmpty() && apiKey.isNotEmpty()) secure.saveDeviceRegistration(DeviceRegistration(deviceId, pushEndpoint, hermesOrigin, apiKey))
                return
            }
            old ?: return
            secure.saveDeviceRegistration(old.copy(
                deviceId = deviceId, pushEndpoint = pushEndpoint,
                hermesOrigin = hermesOrigin.ifEmpty { old.hermesOrigin }, apiKey = apiKey.ifEmpty { old.apiKey },
            ))
            return
        }
        secure.saveDeviceId(deviceId); secure.savePushEndpoint(pushEndpoint)
        if (hermesOrigin.isNotEmpty()) secure.savePushOrigin(hermesOrigin)
        if (apiKey.isNotEmpty()) secure.savePushApiKey(apiKey)
        if (hermesOrigin.isNotEmpty() && apiKey.isNotEmpty()) {
            secure.saveDeviceRegistration(DeviceRegistration(deviceId, pushEndpoint, hermesOrigin, apiKey, secure.loadDeviceSecret()))
        }
    }
    fun updateCredentials(apiKey: String) {
        if (apiKey.isNotEmpty()) {
            if (secure.hasDeviceRegistrationMarker()) {
                secure.loadDeviceRegistration()?.let { secure.saveDeviceRegistration(it.copy(apiKey = apiKey)) }
            } else secure.savePushApiKey(apiKey)
        }
    }

    fun clear() {
        secure.saveDeviceRegistrationTombstone()
        secure.clearDeviceId()
        secure.clearPushEndpoint()
        secure.clearPushOrigin()
        secure.clearPushApiKey()
        secure.clearDeviceSecret()
    }
}
