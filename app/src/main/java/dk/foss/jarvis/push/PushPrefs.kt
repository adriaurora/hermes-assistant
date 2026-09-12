package dk.foss.jarvis.push

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dk.foss.jarvis.events.DeliveredEventLog

private val Context.pushDataStore by preferencesDataStore(name = "push_prefs")
interface PushState {
    suspend fun isEnabled(): Boolean
    suspend fun disable()
    suspend fun setPendingRevoke(value: Boolean)
    suspend fun isPendingRevoke(): Boolean
    suspend fun setPendingCredentialClear(value: Boolean)
    suspend fun isPendingCredentialClear(): Boolean
    suspend fun setRegistrationState(value: FcmRegistrationState)
}
class PushPrefs constructor(private val store: DataStore<Preferences>) : PushState, TransportChoiceStore {
    constructor(context: Context) : this(context.applicationContext.pushDataStore)
    internal object Keys {
        val ENABLED=booleanPreferencesKey("enabled"); val DISTRIBUTOR=stringPreferencesKey("distributor")
        val REGISTRATION_STATE=stringPreferencesKey("registration_state"); val PENDING_REVOKE=booleanPreferencesKey("pending_revoke")
        val PENDING_CREDENTIAL_CLEAR=booleanPreferencesKey("pending_credential_clear")
        val PUSH_TRANSPORT=stringPreferencesKey("push_transport")
        val DELIVERED_EVENTS=stringPreferencesKey("delivered_events"); val PUSH_PROBE_RESULT=stringPreferencesKey("push_probe_result"); val PUSH_PROBE_AT=longPreferencesKey("push_probe_at")
    }
    val enabled: Flow<Boolean> = store.data.map { it[Keys.ENABLED] ?: false }
    val distributorName: Flow<String?> = store.data.map { it[Keys.DISTRIBUTOR] }
    val registrationState: Flow<FcmRegistrationState> = store.data.map { it[Keys.REGISTRATION_STATE]?.let { v -> runCatching { FcmRegistrationState.valueOf(v) }.getOrNull() } ?: FcmRegistrationState.DISABLED }
    val pendingRevoke: Flow<Boolean> = store.data.map { it[Keys.PENDING_REVOKE] ?: false }
    val pendingCredentialClear: Flow<Boolean> = store.data.map { it[Keys.PENDING_CREDENTIAL_CLEAR] ?: false }
    suspend fun setEnabled(value:Boolean) { store.edit { it[Keys.ENABLED]=value } }
    suspend fun enable()=setEnabled(true)
    override suspend fun disable()=setEnabled(false)
    override suspend fun isEnabled()=enabled.first()
    suspend fun setDistributor(name:String?) { store.edit { if(name==null) it.remove(Keys.DISTRIBUTOR) else it[Keys.DISTRIBUTOR]=name } }
    override suspend fun setRegistrationState(value:FcmRegistrationState) { store.edit { it[Keys.REGISTRATION_STATE]=value.name } }
    override suspend fun setPendingRevoke(value:Boolean) { store.edit { it[Keys.PENDING_REVOKE]=value } }
    override suspend fun isPendingRevoke()=pendingRevoke.first()
    override suspend fun setPendingCredentialClear(value:Boolean) { store.edit { it[Keys.PENDING_CREDENTIAL_CLEAR]=value } }
    override suspend fun isPendingCredentialClear()=pendingCredentialClear.first()
    suspend fun recordDelivered(id:String){store.edit{it[Keys.DELIVERED_EVENTS]=DeliveredEventLog.encode(DeliveredEventLog.append(DeliveredEventLog.decode(it[Keys.DELIVERED_EVENTS]),id))}}
    suspend fun wasDelivered(id:String)=DeliveredEventLog.decode(store.data.first()[Keys.DELIVERED_EVENTS]).contains(id)
    override suspend fun choice()=store.data.first()[Keys.PUSH_TRANSPORT]
    override suspend fun lastProbe()=store.data.first()[Keys.PUSH_PROBE_RESULT]
    override suspend fun lastProbeAt()=store.data.first()[Keys.PUSH_PROBE_AT]
    override suspend fun recordProbe(transport: String, nowMs: Long) {
        store.edit { if (transport.isEmpty()) it.remove(Keys.PUSH_PROBE_RESULT) else it[Keys.PUSH_PROBE_RESULT] = transport; if (nowMs != 0L) it[Keys.PUSH_PROBE_AT] = nowMs }
    }
}
enum class FcmRegistrationState { DISABLED, REGISTERING, ENABLED, ERROR, UNREGISTERING }
