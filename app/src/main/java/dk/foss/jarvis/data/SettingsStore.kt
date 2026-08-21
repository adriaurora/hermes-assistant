package dk.foss.jarvis.data

import android.content.Context
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

/** User configuration: how to reach Hermes. */
data class JarvisSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && apiKey.isNotEmpty()
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        // LEGACY location of the plaintext API key; migrated into [SecureStore]
        // on first read and removed. Never written again.
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        // Legacy keys from the removed wake-word / ElevenLabs era. No longer
        // read or written; kept listed so a future cleanup knows they exist.
        val ELEVEN_KEY = stringPreferencesKey("eleven_key")
        val ELEVEN_VOICE = stringPreferencesKey("eleven_voice")
        val WAKE_ENABLED = booleanPreferencesKey("wake_enabled")
    }

    private val secure = SecureStore.get(context)
    private val purgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The Hermes bearer token lives only in [SecureStore] (Keystore-encrypted).
     * If a legacy plaintext key is found in DataStore it is imported once and
     * the plaintext value is purged asynchronously.
     */
    val settings: Flow<JarvisSettings> = context.dataStore.data.map { p ->
        val legacy = p[Keys.API_KEY]
        val token = secure.importOnce(legacy)
        if (token != null && legacy != null) {
            purgeScope.launch {
                context.dataStore.edit {
                    it.remove(Keys.API_KEY)
                    it.remove(Keys.ELEVEN_KEY)
                    it.remove(Keys.ELEVEN_VOICE)
                    it.remove(Keys.WAKE_ENABLED)
                }
            }
        }
        JarvisSettings(
            baseUrl = p[Keys.BASE_URL] ?: "",
            apiKey = token.orEmpty(),
            // Empty, or the previous default ("kimi-for-coding"), migrates to DEFAULT_MODEL
            // so existing installs flip to the new model; a deliberately-set model is kept.
            model = (p[Keys.MODEL] ?: "").let { if (it.isEmpty() || it == LEGACY_MODEL) DEFAULT_MODEL else it },
        )
    }

    /**
     * Save connection settings. [apiKey] semantics:
     * - null  → keep whatever token is currently stored;
     * - ""    → clear the stored token;
     * - other → replace the stored token.
     * Any legacy plaintext key in DataStore is removed either way.
     */
    suspend fun updateConnection(baseUrl: String, apiKey: String?, model: String) {
        context.dataStore.edit { p ->
            p[Keys.BASE_URL] = baseUrl.trim().trimEnd('/')
            if (apiKey != null) {
                val trimmed = apiKey.trim()
                if (trimmed.isEmpty()) secure.clearToken() else secure.saveToken(trimmed)
            } else {
                secure.importOnce(p[Keys.API_KEY]) // preserve a never-imported legacy key
            }
            p.remove(Keys.API_KEY)
            p[Keys.MODEL] = model.trim().ifEmpty { DEFAULT_MODEL }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "mimo-v2.5-pro-ultraspeed"
        const val LEGACY_MODEL = "kimi-for-coding" // prior default; migrated to DEFAULT_MODEL
    }
}
