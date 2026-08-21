package dk.foss.jarvis.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "jarvis_settings")

/** User configuration: how to reach Hermes. Model selection is left to Hermes. */
data class JarvisSettings(
    val baseUrl: String,
    val apiKey: String,
) {
    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && apiKey.isNotEmpty()
}

class SettingsStore internal constructor(
    private val store: DataStore<Preferences>,
    private val secure: SecureStore,
) {
    /** Android entry point: app DataStore + Keystore-backed SecureStore. */
    constructor(context: Context) : this(context.dataStore, SecureStore.get(context))

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        // LEGACY location of the plaintext API key; migrated into [SecureStore]
        // on first read and removed. Never written again.
        val API_KEY = stringPreferencesKey("api_key")
        // LEGACY keys from removed features (model override, wake word,
        // ElevenLabs). Purged on first migration run; never read.
        val MODEL = stringPreferencesKey("model")
        val ELEVEN_KEY = stringPreferencesKey("eleven_key")
        val ELEVEN_VOICE = stringPreferencesKey("eleven_voice")
        val WAKE_ENABLED = booleanPreferencesKey("wake_enabled")
    }

    private val purgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The Hermes bearer token lives only in [SecureStore] (Keystore-encrypted).
     * If a legacy plaintext key is found in DataStore it is imported once and
     * the plaintext value is purged asynchronously.
     */
    val settings: Flow<JarvisSettings> = store.data.map { p ->
        val legacy = p[Keys.API_KEY]
        val token = secure.importOnce(legacy)
        if (token != null && legacy != null) {
            purgeScope.launch { purgeLegacyKeys() }
        }
        JarvisSettings(
            baseUrl = p[Keys.BASE_URL] ?: "",
            apiKey = token.orEmpty(),
        )
    }

    /**
     * Save connection settings. [apiKey] semantics:
     * - null  → keep whatever token is currently stored;
     * - ""    → clear the stored token;
     * - other → replace the stored token.
     * Any legacy plaintext key in DataStore is removed either way.
     */
    suspend fun updateConnection(baseUrl: String, apiKey: String?) {
        store.edit { p ->
            p[Keys.BASE_URL] = baseUrl.trim().trimEnd('/')
            if (apiKey != null) {
                val trimmed = apiKey.trim()
                if (trimmed.isEmpty()) secure.clearToken() else secure.saveToken(trimmed)
            } else {
                secure.importOnce(p[Keys.API_KEY]) // preserve a never-imported legacy key
            }
            p.remove(Keys.API_KEY)
            p.remove(Keys.MODEL)
        }
    }

    /** Remove the legacy plaintext key + keys from removed features. */
    private suspend fun purgeLegacyKeys() {
        store.edit {
            it.remove(Keys.API_KEY)
            it.remove(Keys.MODEL)
            it.remove(Keys.ELEVEN_KEY)
            it.remove(Keys.ELEVEN_VOICE)
            it.remove(Keys.WAKE_ENABLED)
        }
    }

    companion object
}
