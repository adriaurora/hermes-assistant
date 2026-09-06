package dk.foss.jarvis.receivers

import android.content.Context
import android.content.Intent
import android.os.Build
import dk.foss.jarvis.MainActivity
import android.util.Log
import dk.foss.jarvis.data.DeviceRegistryStore
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.events.NotificationDeduper
import dk.foss.jarvis.hermes.EventClient
import dk.foss.jarvis.hermes.EventRpcClient
import dk.foss.jarvis.hermes.EventFetchException
import dk.foss.jarvis.hermes.HermesHttpException
import dk.foss.jarvis.data.DeviceRegistration
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
import dk.foss.jarvis.push.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Transport-independent ingress boundary used by the native FCM service. */
object PushIngress {
    private val deduper = NotificationDeduper()
    private suspend fun resolveTransport(context: Context, settings: dk.foss.jarvis.data.JarvisSettings): PushTransport = withContext(Dispatchers.IO) {
        TransportSelector(PushPrefs(context), probe = { baseUrl, apiKey -> EventRpcClient(baseUrl, apiKey).probe().getOrNull() }).select(settings.baseUrl, settings.apiKey)
    }
    private fun legacyClient(settings: dk.foss.jarvis.data.JarvisSettings, deviceId: String?) = EventClient(settings.baseUrl, settings.apiKey, deviceId)
    private fun rpcClient(settings: dk.foss.jarvis.data.JarvisSettings, registration: DeviceRegistration?) = EventRpcClient(settings.baseUrl, settings.apiKey, registration?.deviceId, registration?.deviceSecret)

    suspend fun ingestEvent(context: Context, eventId: String): Boolean =
        ingestEventOutcome(context, eventId) == GateOutcome.NOTIFIED

    suspend fun ingestEventOutcome(context: Context, eventId: String): GateOutcome {
        val settings = SettingsStore(context).settings.first()
        val device = DeviceRegistryStore(context).load()
        val prefs = PushPrefs(context)
        if (!prefs.isEnabled()) return GateOutcome.DISABLED
        if (!settings.isConfigured) return GateOutcome.DISABLED
        if (device == null) { Log.w("HermesPush", "push received without device registration"); return GateOutcome.NO_DEVICE }
        val transport = resolveTransport(context, settings)
        if (transport == PushTransport.V1 && device.deviceSecret.isNullOrBlank()) return GateOutcome.NO_DEVICE
        val client = if (transport == PushTransport.V1) rpcClient(settings, device) else legacyClient(settings, device.deviceId)
        val gate = PushGate(PushDeps(settings.isConfigured, device.deviceId, client, deduper, notify = { envelope, id ->
            if (!NotificationPermission.ensure(context)) DeliveryOutcome.PERMISSION_DENIED
            else runCatching { postReminderNotification(context, envelope, id) }
                .fold({ DeliveryOutcome.SUCCESS }, { DeliveryOutcome.POST_FAILURE })
        }, wasDelivered = { prefs.wasDelivered(it) }, onDelivered = { prefs.recordDelivered(it) }))
        return gate.handlePull(eventId)
    }

    suspend fun ingestPending(context: Context): Int {
        val settings = SettingsStore(context).settings.first()
        val prefs = PushPrefs(context)
        val device = DeviceRegistryStore(context).load() ?: return 0
        if (!prefs.isEnabled() || !settings.isConfigured) return 0
        val transport = resolveTransport(context, settings)
        if (transport == PushTransport.V1 && device.deviceSecret.isNullOrBlank()) return 0
        var delivered = 0
        val client = if (transport == PushTransport.V1) rpcClient(settings, device) else legacyClient(settings, device.deviceId)
        val dispatcher = EventDispatcher(client, deduper, notify = { envelope, id ->
            if (!NotificationPermission.ensure(context)) error("notification permission denied")
            postReminderNotification(context, envelope, id); delivered++
        }, wasDelivered = { prefs.wasDelivered(it) }, onDelivered = { prefs.recordDelivered(it) })
        return dispatcher.onPendingSync().getOrDefault(0).coerceAtMost(delivered)
    }

    /** Same operation as ingestPending, retaining failure for a retrying worker. */
    suspend fun syncPending(context: Context): Result<Int> {
        val settings = SettingsStore(context).settings.first()
        val prefs = PushPrefs(context)
        val device = DeviceRegistryStore(context).load() ?: return Result.success(0)
        if (!prefs.isEnabled() || !settings.isConfigured) return Result.success(0)
        val transport = resolveTransport(context, settings)
        if (transport == PushTransport.V1 && device.deviceSecret.isNullOrBlank()) return Result.success(0)
        val client = if (transport == PushTransport.V1) rpcClient(settings, device) else legacyClient(settings, device.deviceId)
        val dispatcher = EventDispatcher(client, deduper, notify = { envelope, id ->
            if (!NotificationPermission.ensure(context)) error("notification permission denied")
            postReminderNotification(context, envelope, id)
        }, wasDelivered = { prefs.wasDelivered(it) }, onDelivered = { prefs.recordDelivered(it) })
        return dispatcher.onPendingSync()
    }

    suspend fun onFcmToken(context: Context, token: String): TokenSyncOutcome {
        val prefs = PushPrefs(context)
        if (!prefs.isEnabled()) return TokenSyncOutcome.DISABLED
        val settings = SettingsStore(context).settings.first()
        if (!settings.isConfigured) { Log.w("HermesPush", "endpoint received without Hermes configuration"); return TokenSyncOutcome.PERMANENT }
        val registry = DeviceRegistryStore(context)
        val existing = registry.load()
        val transport = resolveTransport(context, settings)
        fun failure(e: Throwable): TokenSyncOutcome = if ((e as? HermesHttpException)?.statusCode == 401 || (e as? HermesHttpException)?.statusCode == 404) TokenSyncOutcome.PERMANENT else TokenSyncOutcome.RETRYABLE
        if (transport == PushTransport.LEGACY) {
            val c = legacyClient(settings, existing?.deviceId)
            val result = if (existing == null) c.registerFcmDevice(token).map { it.device_id }.also { it.onSuccess { registry.save(it, token, settings.baseUrl, settings.apiKey); prefs.setProtocol(PushProtocol.LEGACY); schedulePendingSync(context) } } else c.updateFcmToken(token).map { existing.deviceId }.also { it.onSuccess { registry.save(it, token, settings.baseUrl, settings.apiKey); prefs.setProtocol(PushProtocol.LEGACY) } }
            return result.fold({ if (existing == null) TokenSyncOutcome.REGISTERED else TokenSyncOutcome.UPDATED }, { failure(it) })
        }
        suspend fun registerFresh(): TokenSyncOutcome {
            val label = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
            val result = EventRpcClient(settings.baseUrl, settings.apiKey).register(label, token)
            return result.fold({ r -> if (r.device_secret.isNullOrBlank()) TokenSyncOutcome.PERMANENT else { registry.saveV1(r.device_id, r.device_secret, token, settings.baseUrl, settings.apiKey); prefs.setProtocol(PushProtocol.V1); schedulePendingSync(context); TokenSyncOutcome.REGISTERED } }, { e ->
                val x = e as? EventFetchException
                when (RpcRetryPolicy.classify(x?.kind, x?.statusCode, x?.rpcCode)) { RpcErrorClass.PERMANENT -> TokenSyncOutcome.PERMANENT; else -> TokenSyncOutcome.RETRYABLE }
            })
        }
        return when (val action = EnrollmentPolicy.decide(PushTransport.V1, existing != null, existing?.deviceSecret?.isNotBlank() == true)) {
            EnrollmentAction.None -> TokenSyncOutcome.PERMANENT
            is EnrollmentAction.RegisterFresh -> { if (action.legacyRevokeFirst && existing != null) runCatching { legacyClient(settings, existing.deviceId).revokeDevice() }; registerFresh() }
            EnrollmentAction.UpdateToken -> rpcClient(settings, existing).updateToken(token).fold({ registry.save(existing!!.deviceId, token, settings.baseUrl, settings.apiKey); TokenSyncOutcome.UPDATED }, { e ->
                val x = e as? EventFetchException
                when (RpcRetryPolicy.classify(x?.kind, x?.statusCode, x?.rpcCode)) { RpcErrorClass.REENROLL -> { registry.clear(); prefs.setProtocol(PushProtocol.LEGACY); registerFresh() }; RpcErrorClass.PERMANENT -> TokenSyncOutcome.PERMANENT; else -> TokenSyncOutcome.RETRYABLE }
            })
        }
    }

    suspend fun schedulePendingSync(context: Context) {
        val prefs = PushPrefs(context); if (!prefs.isEnabled()) return
        val settings = SettingsStore(context).settings.first(); if (!settings.isConfigured) return
        if (DeviceRegistryStore(context).load() == null) return
        WorkManager.getInstance(context).enqueueUniqueWork("hermes-pending-sync", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<FcmPendingWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
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
