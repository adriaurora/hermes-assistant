package dk.foss.jarvis.push

import android.content.Context
import dk.foss.jarvis.data.DeviceRegistryStore
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.EventClient
import kotlinx.coroutines.flow.first

/** Native FCM registration lifecycle. */
object FcmLifecycle {
    suspend fun enable(context: Context) {
        val prefs = PushPrefs(context)
        prefs.enable()
        prefs.setRegistrationState(FcmRegistrationState.REGISTERING)
        FcmTokenRegistration.enqueueCurrent(context)
    }

    suspend fun disable(context: Context) {
        val app = context.applicationContext
        val prefs = PushPrefs(app)
        val settings = SettingsStore(app).settings.first()
        val registration = DeviceRegistryStore(app).load()
        if (settings.isConfigured && registration != null) {
            EventClient(settings.baseUrl, settings.apiKey, registration.deviceId).revokeDevice()
        }
        DeviceRegistryStore(app).clear()
        prefs.disable()
        prefs.setRegistrationState(FcmRegistrationState.DISABLED)
    }
}
