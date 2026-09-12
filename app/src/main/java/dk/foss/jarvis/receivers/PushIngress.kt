package dk.foss.jarvis.receivers

import android.content.Context
import android.content.Intent
import android.os.Build
import dk.foss.jarvis.MainActivity
import android.util.Log
import dk.foss.jarvis.data.DeviceRegistryStore
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.events.NotificationDeduper
import dk.foss.jarvis.hermes.EventRpcClient
import dk.foss.jarvis.hermes.EventFetchException
import dk.foss.jarvis.hermes.HermesHttpException
import dk.foss.jarvis.data.DeviceRegistration
import dk.foss.jarvis.data.RegistryState
import dk.foss.jarvis.notifications.EventDispatcher
import dk.foss.jarvis.notifications.NotificationPermission
import dk.foss.jarvis.notifications.postReminderNotification
import dk.foss.jarvis.push.GateOutcome
import dk.foss.jarvis.push.DeliveryOutcome
import dk.foss.jarvis.push.PushDeps
import dk.foss.jarvis.push.PushGate
import dk.foss.jarvis.push.PushPrefs
import dk.foss.jarvis.push.RpcErrorClass
import dk.foss.jarvis.push.RpcRetryPolicy
import dk.foss.jarvis.push.TokenSyncOutcome
import dk.foss.jarvis.push.EnrollmentAction
import dk.foss.jarvis.push.EnrollmentPolicy
import dk.foss.jarvis.push.FcmPendingWorker
import dk.foss.jarvis.push.FcmTokenRegistration
import dk.foss.jarvis.push.FcmRegistrationState
import dk.foss.jarvis.push.PushTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Transport-independent ingress boundary used by the native FCM service. */
object PushIngress {
    private val deduper = NotificationDeduper()
    private suspend fun resolveTransport(): String = withContext(Dispatchers.IO) {
        PushTransport.V1
    }
    private fun rpcClient(settings: dk.foss.jarvis.data.JarvisSettings, registration: DeviceRegistration?) = EventRpcClient(settings.baseUrl, settings.apiKey, registration?.deviceId, registration?.deviceSecret)

    suspend fun ingestEvent(context: Context, eventId: String): Boolean =
        ingestEventOutcome(context, eventId) == GateOutcome.NOTIFIED

    suspend fun ingestEventOutcome(context: Context, eventId: String): GateOutcome {
        val settings = SettingsStore(context).settings.first()
        val device = (DeviceRegistryStore(context).loadOrMigrate(settings) as? RegistryState.Registered)?.registration
        val prefs = PushPrefs(context)
        if (!prefs.isEnabled()) return GateOutcome.DISABLED
        if (prefs.isPendingRevoke() || prefs.isPendingCredentialClear()) return GateOutcome.DISABLED
        if (!settings.isConfigured) return GateOutcome.DISABLED
        if (device == null) { Log.w("HermesPush", "push received without device registration"); return GateOutcome.NO_DEVICE }
        val transport = resolveTransport()
        if (transport == PushTransport.V1 && device.deviceSecret.isNullOrBlank()) return GateOutcome.NO_DEVICE
        val client = rpcClient(settings, device)
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
        val device = (DeviceRegistryStore(context).loadOrMigrate(settings) as? RegistryState.Registered)?.registration ?: return 0
        if (!prefs.isEnabled() || prefs.isPendingRevoke() || prefs.isPendingCredentialClear() || !settings.isConfigured) return 0
        val transport = resolveTransport()
        if (transport == PushTransport.V1 && device.deviceSecret.isNullOrBlank()) return 0
        var delivered = 0
        val client = rpcClient(settings, device)
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
        val device = (DeviceRegistryStore(context).loadOrMigrate(settings) as? RegistryState.Registered)?.registration ?: return Result.success(0)
        if (!prefs.isEnabled() || prefs.isPendingRevoke() || prefs.isPendingCredentialClear() || !settings.isConfigured) return Result.success(0)
        val transport = resolveTransport()
        if (transport == PushTransport.V1 && device.deviceSecret.isNullOrBlank()) return Result.success(0)
        val client = rpcClient(settings, device)
        val dispatcher = EventDispatcher(client, deduper, notify = { envelope, id ->
            if (!NotificationPermission.ensure(context)) error("notification permission denied")
            postReminderNotification(context, envelope, id)
        }, wasDelivered = { prefs.wasDelivered(it) }, onDelivered = { prefs.recordDelivered(it) })
        return dispatcher.onPendingSync()
    }

    suspend fun onFcmToken(context: Context, token: String): TokenSyncOutcome {
        val prefs = PushPrefs(context)
        if (!dk.foss.jarvis.push.LifecycleGuards.canAcceptTokenSync(prefs.isEnabled(), prefs.isPendingRevoke(), prefs.isPendingCredentialClear())) return TokenSyncOutcome.DISABLED
        if (prefs.isPendingRevoke() || prefs.isPendingCredentialClear()) return TokenSyncOutcome.DISABLED
        val settings = SettingsStore(context).settings.first()
        if (!settings.isConfigured) { Log.w("HermesPush", "endpoint received without Hermes configuration"); return TokenSyncOutcome.PERMANENT }
        val registry = DeviceRegistryStore(context)
        val state = registry.loadOrMigrate(settings)
        val existing = (state as? RegistryState.Registered)?.registration
        fun failure(e: Throwable): TokenSyncOutcome = when {
            (e as? HermesHttpException)?.statusCode == 404 -> TokenSyncOutcome.PERMANENT
            else -> TokenSyncOutcome.RETRYABLE
        }
        suspend fun registerFresh(legacyDeviceId: String? = null): TokenSyncOutcome {
            val label = Build.MODEL.takeIf { it.isNotBlank() } ?: "Android"
            val result = EventRpcClient(settings.baseUrl, settings.apiKey).register(label, token, legacyDeviceId = legacyDeviceId)
            return result.fold({ r -> if (r.device_secret.isNullOrBlank()) TokenSyncOutcome.PERMANENT else { registry.saveV1(r.device_id, r.device_secret, token, settings.baseUrl, settings.apiKey); schedulePendingSync(context); TokenSyncOutcome.REGISTERED } }, { e ->
                val x = e as? EventFetchException
                when (RpcRetryPolicy.classify(x?.kind, x?.statusCode, x?.rpcCode)) { RpcErrorClass.PERMANENT -> TokenSyncOutcome.PERMANENT; else -> TokenSyncOutcome.RETRYABLE }
            })
        }
        val hasSecret = existing?.deviceSecret?.isNotBlank() == true
        val legacyClaim = when (state) {
            is RegistryState.Registered -> if (!hasSecret) existing?.deviceId else null
            RegistryState.Empty -> null
        }
        return when (val action = EnrollmentPolicy.decide(existing != null, hasSecret)) {
            EnrollmentAction.None -> TokenSyncOutcome.PERMANENT
            is EnrollmentAction.RegisterFresh -> {
                if (action.legacyRevokeFirst && existing != null) runCatching { EventRpcClient(existing.hermesOrigin, existing.apiKey, existing.deviceId, existing.deviceSecret).revoke() }
                registerFresh(legacyClaim)
            }
            EnrollmentAction.UpdateToken -> rpcClient(settings, existing).updateToken(token).fold({ registry.save(existing!!.deviceId, token, existing.hermesOrigin, settings.apiKey); TokenSyncOutcome.UPDATED }, { e ->
                val x = e as? EventFetchException
                when (RpcRetryPolicy.classify(x?.kind, x?.statusCode, x?.rpcCode)) { RpcErrorClass.REENROLL -> {
                    registry.clear(); registerFresh()
                }; RpcErrorClass.PERMANENT -> TokenSyncOutcome.PERMANENT; else -> TokenSyncOutcome.RETRYABLE }
            })
        }
    }

    suspend fun scheduleStartupWork(context: Context) {
        schedulePendingSync(context)
        val prefs = PushPrefs(context)
        if (!prefs.isEnabled()) return
        if (prefs.isPendingRevoke() || prefs.isPendingCredentialClear()) {
            dk.foss.jarvis.push.FcmRevokeWorker.schedule(context)
            return
        }
        val state = prefs.registrationState.first()
        if (state != FcmRegistrationState.ENABLED) FcmTokenRegistration.enqueueCurrent(context)
    }

    suspend fun schedulePendingSync(context: Context) {
        val prefs = PushPrefs(context); if (!prefs.isEnabled()) return
        if (prefs.isPendingRevoke() || prefs.isPendingCredentialClear()) return
        val settings = SettingsStore(context).settings.first(); if (!settings.isConfigured) return
        if ((DeviceRegistryStore(context).loadOrMigrate(settings) as? RegistryState.Registered)?.registration == null) return
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork("hermes-pending-sync", androidx.work.ExistingWorkPolicy.KEEP, androidx.work.OneTimeWorkRequestBuilder<dk.foss.jarvis.push.FcmPendingWorker>().setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build())
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
