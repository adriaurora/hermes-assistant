package dk.foss.jarvis.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dk.foss.jarvis.push.FcmLifecycle
import dk.foss.jarvis.push.FcmConnectionEffects
import dk.foss.jarvis.push.FcmRevokeWorker
import dk.foss.jarvis.push.FcmTokenRegistration
import dk.foss.jarvis.push.PushPrefs
import androidx.work.WorkManager

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
    private val onConnectionChanged: suspend (JarvisSettings, JarvisSettings) -> Unit = { _, _ -> },
    private val withConnectionLock: suspend (suspend () -> Unit) -> Unit = { block -> block() },
) {
    /** Android entry point: app DataStore + Keystore-backed SecureStore. */
    constructor(context: Context) : this(context.dataStore, SecureStore.get(context), { old, new ->
            val app = context.applicationContext
            FcmConnectionEffects(PushPrefs(app), DeviceRegistryStore(app), { FcmRevokeWorker.schedule(app) }, { WorkManager.getInstance(app).cancelUniqueWork(FcmTokenRegistration.WORK_NAME) }).onConnectionChanged(old, new)
        }, { block -> FcmLifecycle.withLock { block() } })

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
        // Insecure HTTP origin approvals (per-endpoint, not per key).
        // Stored as a string-set for future multi-endpoint support, but only one is ever active.
        val APPROVED_HTTP_ORIGINS = stringSetPreferencesKey("approved_http_origins")
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
        val normalized = baseUrl.trim().trimEnd('/')
        withConnectionLock {
            val old = settings.first()
            val newToken = when (apiKey) {
                null -> old.apiKey
                else -> apiKey.trim()
            }
            // Persist the new connection while lifecycle lock is held. The
            // callback schedules revoke only after this edit, so a follow-up
            // registration can only observe B, never the old A settings.
            store.edit { p ->
                p[Keys.BASE_URL] = normalized
                if (apiKey != null) {
                    val trimmed = apiKey.trim()
                    if (trimmed.isEmpty()) secure.clearToken() else secure.saveToken(trimmed)
                } else {
                    secure.importOnce(p[Keys.API_KEY]) // preserve a never-imported legacy key
                }
                p.remove(Keys.API_KEY)
                p.remove(Keys.MODEL)
            }
            onConnectionChanged(old, JarvisSettings(normalized, newToken))
        }
    }

    // ─── HTTP origin approvals ──────────────────────────────────────────────

    /**
     * Returns the set of HTTP origins that have been explicitly approved.
     * Origins are the normalised form from [dk.foss.jarvis.hermes.originIdentity].
     */
    val approvedHttpOrigins: Flow<Set<String>> = store.data.map { p ->
        p[Keys.APPROVED_HTTP_ORIGINS] ?: emptySet()
    }

    /**
     * Approve an HTTP origin.  Idempotent and non-blocking.
     * The origin must be the normalised form (e.g. "http://10.0.0.1:8642").
     */
    suspend fun approveHttpOrigin(origin: String) {
        store.edit { p ->
            val current = p[Keys.APPROVED_HTTP_ORIGINS] ?: emptySet<String>()
            p[Keys.APPROVED_HTTP_ORIGINS] = current + origin
        }
    }

    /**
     * Revoke approval for an HTTP origin.  Idempotent.
     */
    suspend fun revokeHttpOrigin(origin: String) {
        store.edit { p ->
            val current = p[Keys.APPROVED_HTTP_ORIGINS] ?: emptySet<String>()
            if (origin in current) {
                p[Keys.APPROVED_HTTP_ORIGINS] = current - origin
            }
        }
    }

    /**
     * Remove all HTTP origin approvals (used when credentials are cleared).
     */
    suspend fun clearAllHttpApprovals() {
        store.edit { p -> p.remove(Keys.APPROVED_HTTP_ORIGINS) }
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
