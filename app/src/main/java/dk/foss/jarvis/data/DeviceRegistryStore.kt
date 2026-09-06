package dk.foss.jarvis.data

import android.content.Context

data class DeviceRegistration(val deviceId: String, val pushEndpoint: String, val hermesOrigin: String, val apiKey: String)

/** Keystore-backed local identity and push endpoint for the registered device. */
class DeviceRegistryStore(context: Context) {
    private val secure = SecureStore.get(context)

    fun load(): DeviceRegistration? {
        val id = secure.loadDeviceId() ?: return null
        val endpoint = secure.loadPushEndpoint() ?: return null
        val origin = secure.loadPushOrigin() ?: return null
        val apiKey = secure.loadPushApiKey() ?: return null
        return DeviceRegistration(id, endpoint, origin, apiKey)
    }

    fun save(deviceId: String, pushEndpoint: String, hermesOrigin: String = "", apiKey: String = "") {
        secure.saveDeviceId(deviceId)
        secure.savePushEndpoint(pushEndpoint)
        if (hermesOrigin.isNotEmpty()) secure.savePushOrigin(hermesOrigin)
        if (apiKey.isNotEmpty()) secure.savePushApiKey(apiKey)
    }

    fun updateCredentials(hermesOrigin: String, apiKey: String) {
        secure.savePushOrigin(hermesOrigin)
        // Keep the last usable credential when the user clears the bearer
        // field; it is still needed to revoke the existing remote record.
        if (apiKey.isNotEmpty()) secure.savePushApiKey(apiKey)
    }

    fun clear() {
        secure.clearDeviceId()
        secure.clearPushEndpoint()
        secure.clearPushOrigin()
        secure.clearPushApiKey()
    }
}
