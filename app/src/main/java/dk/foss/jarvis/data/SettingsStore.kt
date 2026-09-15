package dk.foss.jarvis.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dk.foss.jarvis.hermes.originIdentity
import dk.foss.jarvis.hermes.canonicalEndpointIdentity
import dk.foss.jarvis.net.AndroidApprovedOriginsStore
import dk.foss.jarvis.push.FcmLifecycle
import dk.foss.jarvis.push.FcmConnectionEffects
import dk.foss.jarvis.push.FcmRevokeWorker
import dk.foss.jarvis.push.FcmTokenRegistration
import dk.foss.jarvis.push.PushPrefs
import androidx.work.WorkManager

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
    /**
     * Android entry point: uses the SAME shared DataStore instance that
     * [AndroidApprovedOriginsStore] creates (file `jarvis_settings`).
     * This guarantees a single source of truth — no multiple DataStore
     * instances for the same file.
     */
    constructor(context: Context) : this(
        store = AndroidApprovedOriginsStore.dataStorePreferences(context),
        secure = SecureStore.get(context),
        onConnectionChanged = { old, new ->
            val app = context.applicationContext
            FcmConnectionEffects(PushPrefs(app), DeviceRegistryStore(app), { FcmRevokeWorker.schedule(app) }, { WorkManager.getInstance(app).cancelUniqueWork(FcmTokenRegistration.WORK_NAME) }).onConnectionChanged(old, new)
        },
        withConnectionLock = { block -> FcmLifecycle.withLock { block() } },
    )

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
        // Cleanup allowance: old endpoints pending a revoke worker.
        // Lives in the SAME DataStore as active approvals so that
        // moveForCleanup is a single atomic edit.
        val CLEANUP_HTTP_ORIGINS = stringSetPreferencesKey("cleanup_http_origins")
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
     *
     * If the new [baseUrl] is different from the old one and both use HTTP,
     * the old origin is atomically moved to the cleanup allowance so that
     * the revoke worker can still reach it — but ordinary traffic to the old
     * endpoint is blocked immediately.  This is a single `store.edit`
     * transaction, not a nested read/edit across multiple DataStores.
     */
    suspend fun updateConnection(baseUrl: String, apiKey: String?) {
        val normalized = baseUrl.trim().trimEnd('/')
        withConnectionLock {
            val old = settings.first()

            val newToken = when (apiKey) {
                null -> old.apiKey
                else -> apiKey.trim()
            }
            // Single edit: persist new connection AND move old HTTP origin
            // from active → cleanup.  Both are in the same DataStore so this
            // is one atomic transaction — no nested DataStore read/edit.
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

                // Atomic cleanup of old HTTP origin in the same edit.
                // Compare canonical endpoint identities so that URL-equivalent
                // forms (default port folding, trailing slash, host case)
                // do NOT trigger a cleanup move.  Only real host/port/path/
                // scheme changes do.  Uses URL-only identity — API-key
                // fingerprint is NOT part of endpoint approval.
                val newEndpoint = runCatching { canonicalEndpointIdentity(normalized) }.getOrNull()
                val oldEndpoint = runCatching {
                    canonicalEndpointIdentity(old.baseUrl.trim())
                }.getOrNull()
                if (oldEndpoint != null && newEndpoint != null && oldEndpoint != newEndpoint && old.baseUrl.isNotBlank()) {
                    oldEndpoint.let { endpoint ->
                        val currentApproved: Set<String> = p[Keys.APPROVED_HTTP_ORIGINS] ?: emptySet()
                        val currentCleanup: Set<String> = p[Keys.CLEANUP_HTTP_ORIGINS] ?: emptySet()
                        if (endpoint in currentApproved) {
                            p[Keys.APPROVED_HTTP_ORIGINS] = currentApproved - endpoint
                            p[Keys.CLEANUP_HTTP_ORIGINS] = currentCleanup + endpoint
                        }
                    }
                }
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
     * Returns the set of HTTP origins in the cleanup allowance.
     * These are old endpoints that have been moved out of active approval
     * while a revoke worker is pending.  Read from the same DataStore.
     */
    val cleanupHttpOrigins: Flow<Set<String>> = store.data.map { p ->
        p[Keys.CLEANUP_HTTP_ORIGINS] ?: emptySet()
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
     * Revoke approval for an HTTP origin from BOTH active and cleanup.
     * A single edit clears from both scopes.
     */
    suspend fun revokeHttpOrigin(origin: String) {
        store.edit { p ->
            val currentApproved = p[Keys.APPROVED_HTTP_ORIGINS] ?: emptySet<String>()
            val currentCleanup = p[Keys.CLEANUP_HTTP_ORIGINS] ?: emptySet<String>()
            if (origin in currentApproved) {
                p[Keys.APPROVED_HTTP_ORIGINS] = currentApproved - origin
            }
            if (origin in currentCleanup) {
                p[Keys.CLEANUP_HTTP_ORIGINS] = currentCleanup - origin
            }
        }
    }

    /**
     * Remove all HTTP origin approvals from both active and cleanup.
     * Used when credentials are cleared or when the user wants a full reset.
     */
    suspend fun clearAllHttpApprovals() {
        store.edit { p ->
            p.remove(Keys.APPROVED_HTTP_ORIGINS)
            p.remove(Keys.CLEANUP_HTTP_ORIGINS)
        }
    }

    /**
     * Atomic transition: move [origin] from active approval to the cleanup
     * allowance.  Called by [updateConnection] when the user switches to a
     * different HTTP endpoint — the old origin must stop being reachable for
     * ordinary traffic while the revoke worker is pending.
     *
     * Implemented as a single `store.edit` so both keys are updated atomically.
     * Idempotent: if [origin] is not in active approval it is silently
     * ignored.  If it's already in cleanup allowance nothing happens.
     */
    suspend fun moveForCleanup(origin: String) {
        store.edit { p ->
            val currentApproved = p[Keys.APPROVED_HTTP_ORIGINS] ?: emptySet<String>()
            val currentCleanup = p[Keys.CLEANUP_HTTP_ORIGINS] ?: emptySet<String>()
            if (origin in currentApproved) {
                p[Keys.APPROVED_HTTP_ORIGINS] = currentApproved - origin
                p[Keys.CLEANUP_HTTP_ORIGINS] = currentCleanup + origin
            }
        }
    }

    /**
     * Remove an origin from the cleanup allowance only.
     * Used when the user manually forces cleanup revocation.
     */
    suspend fun removeFromCleanup(origin: String) {
        store.edit { p ->
            val currentCleanup = p[Keys.CLEANUP_HTTP_ORIGINS] ?: emptySet<String>()
            if (origin in currentCleanup) {
                p[Keys.CLEANUP_HTTP_ORIGINS] = currentCleanup - origin
            }
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