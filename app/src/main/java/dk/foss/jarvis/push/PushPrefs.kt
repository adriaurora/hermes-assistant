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

private val Context.pushDataStore by preferencesDataStore(name = "push_prefs")
class PushPrefs(context: Context) {
    private val store = context.applicationContext.pushDataStore
    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val DISTRIBUTOR = stringPreferencesKey("distributor")
        val REGISTRATION_STATE = stringPreferencesKey("registration_state")
        val PENDING_REVOKE = booleanPreferencesKey("pending_revoke")
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
}

enum class FcmRegistrationState { DISABLED, REGISTERING, ENABLED, ERROR, UNREGISTERING }
