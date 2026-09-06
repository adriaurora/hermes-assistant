package dk.foss.jarvis.push

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.preferencesDataStore
import dk.foss.jarvis.events.DeliveredEventLog

private val Context.pushDataStore by preferencesDataStore(name = "push_prefs")
enum class PushProtocol { LEGACY, V1 }

class PushPrefs(context: Context) : TransportChoiceStore {
    private val store = context.applicationContext.pushDataStore
    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val DISTRIBUTOR = stringPreferencesKey("distributor")
        val REGISTRATION_STATE = stringPreferencesKey("registration_state")
        val PENDING_REVOKE = booleanPreferencesKey("pending_revoke")
        val PUSH_PROTOCOL = stringPreferencesKey("push_protocol")
        val PUSH_TRANSPORT = stringPreferencesKey("push_transport")
        val DELIVERED_EVENTS = stringPreferencesKey("delivered_events")
        val PUSH_PROBE_RESULT = stringPreferencesKey("push_probe_result")
        val PUSH_PROBE_AT = androidx.datastore.preferences.core.longPreferencesKey("push_probe_at")
    }
    val enabled: Flow<Boolean> = store.data.map { it[Keys.ENABLED] ?: false }
    val distributorName: Flow<String?> = store.data.map { it[Keys.DISTRIBUTOR] }
    val registrationState: Flow<FcmRegistrationState> = store.data.map {
        it[Keys.REGISTRATION_STATE]?.let { value -> runCatching { FcmRegistrationState.valueOf(value) }.getOrNull() }
            ?: FcmRegistrationState.DISABLED
    }
    val pendingRevoke: Flow<Boolean> = store.data.map { it[Keys.PENDING_REVOKE] ?: false }
    suspend fun setEnabled(value: Boolean) { store.edit { it[Keys.ENABLED] = value } }
    suspend fun enable() = setEnabled(true)
    suspend fun disable() = setEnabled(false)
    suspend fun isEnabled() = enabled.first()
    suspend fun setDistributor(name: String?) { store.edit { if (name == null) it.remove(Keys.DISTRIBUTOR) else it[Keys.DISTRIBUTOR] = name } }
    suspend fun setRegistrationState(value: FcmRegistrationState) { store.edit { it[Keys.REGISTRATION_STATE] = value.name } }
    suspend fun setPendingRevoke(value: Boolean) = store.edit { it[Keys.PENDING_REVOKE] = value }
    suspend fun isPendingRevoke(): Boolean = pendingRevoke.first()
    suspend fun protocol(): PushProtocol = store.data.first()[Keys.PUSH_PROTOCOL]?.let { runCatching { PushProtocol.valueOf(it) }.getOrNull() } ?: PushProtocol.LEGACY
    suspend fun setProtocol(value: PushProtocol) { store.edit { it[Keys.PUSH_PROTOCOL] = value.name } }
    suspend fun recordDelivered(eventId: String) { store.edit { it[Keys.DELIVERED_EVENTS] = DeliveredEventLog.encode(DeliveredEventLog.append(DeliveredEventLog.decode(it[Keys.DELIVERED_EVENTS]), eventId)) } }
    suspend fun wasDelivered(eventId: String): Boolean = DeliveredEventLog.decode(store.data.first()[Keys.DELIVERED_EVENTS]).contains(eventId)
    suspend fun probeResult(): PushTransport? = store.data.first()[Keys.PUSH_PROBE_RESULT]?.let { runCatching { PushTransport.valueOf(it) }.getOrNull() }
    suspend fun setProbeResult(transport: PushTransport?, nowMs: Long? = null) { store.edit { if (transport == null) it.remove(Keys.PUSH_PROBE_RESULT) else it[Keys.PUSH_PROBE_RESULT] = transport.name; if (nowMs != null) it[Keys.PUSH_PROBE_AT] = nowMs } }
    override suspend fun choice(): String? = store.data.first()[Keys.PUSH_TRANSPORT]
    override suspend fun lastProbe(): PushTransport? = probeResult()
    override suspend fun lastProbeAt(): Long? = store.data.first()[Keys.PUSH_PROBE_AT]
    override suspend fun recordProbe(transport: PushTransport, nowMs: Long) { setProbeResult(transport, nowMs) }
}

enum class FcmRegistrationState { DISABLED, REGISTERING, ENABLED, ERROR, UNREGISTERING }
