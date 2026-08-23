package dk.foss.jarvis.data

import android.content.Context

data class DeviceRegistration(val deviceId: String, val pushEndpoint: String)

/** Keystore-backed local identity and push endpoint for the registered device. */
class DeviceRegistryStore(context: Context) {
    private val secure = SecureStore.get(context)

    fun load(): DeviceRegistration? {
        val id = secure.loadDeviceId() ?: return null
        val endpoint = secure.loadPushEndpoint() ?: return null
        return DeviceRegistration(id, endpoint)
    }

    fun save(deviceId: String, pushEndpoint: String) {
        secure.saveDeviceId(deviceId)
        secure.savePushEndpoint(pushEndpoint)
    }

    fun clear() {
        secure.clearDeviceId()
        secure.clearPushEndpoint()
    }
}
