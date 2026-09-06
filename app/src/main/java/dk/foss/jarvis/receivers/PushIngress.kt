package dk.foss.jarvis.receivers

import android.content.Context
import android.content.Intent
import dk.foss.jarvis.MainActivity
import android.util.Log
import dk.foss.jarvis.data.DeviceRegistryStore
import dk.foss.jarvis.data.RegistryState
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.events.NotificationDeduper
import dk.foss.jarvis.hermes.EventClient
import dk.foss.jarvis.hermes.HermesHttpException
import dk.foss.jarvis.notifications.EventDispatcher
import dk.foss.jarvis.notifications.NotificationPermission
import dk.foss.jarvis.notifications.postReminderNotification
import dk.foss.jarvis.push.GateOutcome
import dk.foss.jarvis.push.DeliveryOutcome
import dk.foss.jarvis.push.PushDeps
import dk.foss.jarvis.push.PushGate
import dk.foss.jarvis.push.PushPrefs
import kotlinx.coroutines.flow.first
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dk.foss.jarvis.push.FcmPendingWorker

/** Transport-independent ingress boundary used by the native FCM service. */
object PushIngress {
    private val deduper = NotificationDeduper()
    private fun enqueuePendingSync(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "hermes-pending-sync", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FcmPendingWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
    private fun deps(settings: dk.foss.jarvis.data.JarvisSettings, device: dk.foss.jarvis.data.DeviceRegistration?, notify: (dk.foss.jarvis.events.HermesEventEnvelope, Int) -> DeliveryOutcome): PushDeps =
        PushDeps(settings.isConfigured, device?.deviceId, EventClient(settings.baseUrl, settings.apiKey, device?.deviceId), deduper, notify)

    suspend fun ingestEvent(context: Context, eventId: String): Boolean =
        ingestEventOutcome(context, eventId) == GateOutcome.NOTIFIED

    suspend fun ingestEventOutcome(context: Context, eventId: String): GateOutcome {
        val settings = SettingsStore(context).settings.first()
        val device = (DeviceRegistryStore(context).loadOrMigrate(settings) as? RegistryState.Registered)?.registration
        val prefs = PushPrefs(context)
        if (!prefs.isEnabled()) return GateOutcome.DISABLED
        if (!settings.isConfigured) return GateOutcome.DISABLED
        if (device == null) { Log.w("HermesPush", "push received without device registration"); return GateOutcome.NO_DEVICE }
        val gate = PushGate(deps(settings, device) { envelope, id ->
            if (!NotificationPermission.ensure(context)) DeliveryOutcome.PERMISSION_DENIED
            else runCatching { postReminderNotification(context, envelope, id) }
                .fold({ DeliveryOutcome.SUCCESS }, { DeliveryOutcome.POST_FAILURE })
        })
        return gate.handlePull(eventId)
    }

    suspend fun ingestPending(context: Context): Int {
        val settings = SettingsStore(context).settings.first()
        val device = (DeviceRegistryStore(context).loadOrMigrate(settings) as? RegistryState.Registered)?.registration ?: return 0
        if (!PushPrefs(context).isEnabled() || !settings.isConfigured) return 0
        var delivered = 0
        val dispatcher = EventDispatcher(EventClient(settings.baseUrl, settings.apiKey, device.deviceId), deduper) { envelope, id ->
            if (!NotificationPermission.ensure(context)) error("notification permission denied")
            postReminderNotification(context, envelope, id); delivered++
        }
        return dispatcher.onPendingSync().getOrDefault(0).coerceAtMost(delivered)
    }

    /** Same operation as ingestPending, retaining failure for a retrying worker. */
    suspend fun syncPending(context: Context): Result<Int> {
        val settings = SettingsStore(context).settings.first()
        val device = (DeviceRegistryStore(context).loadOrMigrate(settings) as? RegistryState.Registered)?.registration ?: return Result.success(0)
        if (!PushPrefs(context).isEnabled() || !settings.isConfigured) return Result.success(0)
        val dispatcher = EventDispatcher(EventClient(settings.baseUrl, settings.apiKey, device.deviceId), deduper) { envelope, id ->
            if (!NotificationPermission.ensure(context)) error("notification permission denied")
            postReminderNotification(context, envelope, id)
        }
        return dispatcher.onPendingSync()
    }

    suspend fun onFcmToken(context: Context, token: String): Boolean {
        if (!PushPrefs(context).isEnabled()) return false
        if (PushPrefs(context).isPendingRevoke() || PushPrefs(context).isPendingCredentialClear()) return false
        val settings = SettingsStore(context).settings.first()
        if (!settings.isConfigured) { Log.w("HermesPush", "endpoint received without Hermes configuration"); return false }
        val registry = DeviceRegistryStore(context)
        return when (val state = registry.loadOrMigrate(settings)) {
            is RegistryState.LegacyPending -> false
            is RegistryState.Empty -> EventClient(settings.baseUrl, settings.apiKey, null).registerFcmDevice(token)
                .onSuccess {
                    registry.save(it.device_id, token, settings.baseUrl, settings.apiKey)
                    enqueuePendingSync(context)
                }
                .onFailure { Log.e("HermesPush", "FCM device registration failed", it) }.isSuccess
            is RegistryState.Registered -> {
                val existing = state.registration
                val update = EventClient(settings.baseUrl, settings.apiKey, existing.deviceId).updateFcmToken(token)
                val err = update.exceptionOrNull()
                // postJson surfaces non-2xx as IllegalStateException("HTTP <code>: …");
                // revokeDevice uses HermesHttpException. Accept both shapes.
                val notFound = (err as? HermesHttpException)?.statusCode == 404 ||
                    (err as? IllegalStateException)?.message?.startsWith("HTTP 404") == true
                when {
                    update.isSuccess -> {
                        // Refresh the pinned credential but keep the pinned origin:
                        // the remote device belongs to the origin that created it.
                        registry.save(existing.deviceId, token, existing.hermesOrigin, settings.apiKey)
                        true
                    }
                    notFound -> {
                        // The pinned device does not exist on the active origin.
                        EventClient(settings.baseUrl, settings.apiKey, null).registerFcmDevice(token)
                            .onSuccess {
                                registry.save(it.device_id, token, settings.baseUrl, settings.apiKey)
                                enqueuePendingSync(context)
                            }
                            .onFailure { Log.e("HermesPush", "FCM device registration failed", it) }.isSuccess
                    }
                    else -> { Log.e("HermesPush", "FCM token update failed", update.exceptionOrNull()); false }
                }
            }
        }
    }

    suspend fun onUnregistered(context: Context) {
        DeviceRegistryStore(context).clear()
        Log.i("HermesPush", "push registration cleared")
    }

    suspend fun fromNotificationIntent(context: Context, intent: Intent): Boolean {
        val eventId = intent.getStringExtra("event_id") ?: return false
        if (!intent.getBooleanExtra("from_notification", false)) return false
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtras(intent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        return eventId.isNotBlank()
    }
}
