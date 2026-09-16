package dk.foss.jarvis.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import dk.foss.jarvis.hermes.canonicalEndpointIdentity
import dk.foss.jarvis.net.AndroidApprovedOriginsStore
import dk.foss.jarvis.net.BlockedRequest
import dk.foss.jarvis.net.NetworkGate
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

/**
 * Thrown when [SettingsStore.updateConnection] refuses to persist
 * invalid settings. The previous (still-valid) settings are preserved.
 */
open class InvalidConnectionSettings(val reason: String) : IllegalArgumentException(reason)

/**
 * Thrown when the caller tries to downgrade from a secure (HTTPS) connection
 * to an insecure one (HTTP) without explicit approval.  Protects against
 * prompt-suppression attacks where a malicious HTTPS page could set an HTTP
 * URL in the settings without the user seeing a consent dialog.
 */
class HttpDowngradeNotAllowed : InvalidConnectionSettings(
    "Downgrading from HTTPS to HTTP requires explicit approval in Settings."
)

/**
 * Thrown when [SettingsStore] is in a fail-closed state (corrupted journal or
 * inconsistent state).  Consumers that receive this should NOT make network
 * requests — the user must re-enter credentials.
 */
class FailClosedException(reason: String) : RuntimeException(reason)

/**
 * Thrown when the journal cannot be written (SecureStore unavailable).
 * The previous settings are preserved.
 */
class JournalWriteFailed(message: String = "Failed to write connection-change journal", cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Sealed interface describing the outcome of a connection-change recovery.
 *
 * - [Recovered]: the transition was applied and the journal is cleared.
 *   App has consistent settings.
 * - [RolledBack]: the transition never applied (URL unchanged). Old settings
 *   are intact, journal cleaned up.
 * - [FailClosed]: the journal exists but state is inconsistent (URL differs
 *   from both old and new, or SecureStore unavailable). Credentials are NOT
 *   exposed.
 */
sealed interface RecoveryResult {
    /** Transition was applied and completed. */
    object Recovered : RecoveryResult
    /** Transition never started; old settings intact, journal cleaned up. */
    object RolledBack : RecoveryResult
    /** State is inconsistent; credentials are NOT exposed. */
    object FailClosed : RecoveryResult
}

/**
 * Journal blob parsed from SecureStore.  All sensitive fields are encrypted
 * in the blob; parse failures → fail-closed.
 */
private data class JournalBlob(
    val version: Int,
    val oldUrl: String,
    val newUrl: String,
    val operation: String,  // KEEP | CLEAR | REPLACE
    val newToken: String?,  // present only when operation == REPLACE
    val phase: String = "STAGED",
) {
    companion object {
        const val VERSION = 1
        const val OP_KEEP = "KEEP"
        const val OP_CLEAR = "CLEAR"
        const val OP_REPLACE = "REPLACE"

        /** Parse the encrypted JSON blob.  Returns null if corrupted. */
        fun fromJson(json: String): JournalBlob? = runCatching {
            val obj = JSONObject(json)
            if (obj.getInt("v") != VERSION) return@runCatching null
            val oldUrl = obj.getString("u0")
            val newUrl = obj.getString("u1")
            val op = obj.getString("op")
            if (op !in listOf(OP_KEEP, OP_CLEAR, OP_REPLACE)) return@runCatching null
            val newToken = if (obj.has("t")) obj.getString("t").takeIf { it.isNotBlank() } else null
            JournalBlob(version = VERSION, oldUrl = oldUrl, newUrl = newUrl, operation = op, newToken = newToken,
                phase = obj.optString("phase", "STAGED"))
        }.getOrNull()

        /** Serialize to JSON blob for SecureStore encryption. */
        fun toJson(blob: JournalBlob): String {
            val obj = JSONObject().apply {
                put("v", blob.version)
                put("u0", blob.oldUrl)
                put("u1", blob.newUrl)
                put("op", blob.operation)
                if (blob.newToken != null) put("t", blob.newToken)
                put("phase", blob.phase)
            }
            return obj.toString()
        }
    }
}

/**
 * SettingsStore: user configuration for reaching Hermes.
 *
 * ### Connection change journal (R03)
 *
 * Every [updateConnection] call follows a durable protocol:
 * 1. Validate URL before any write.
 * 2. Stage a journal entry in [SecureStore] (encrypted, synchronous commit).
 *    If staging fails → abort, no settings modified.
 * 3. Single `DataStore.edit` for URL only (no SecureStore calls inside).
 * 4. Write the token outside the DataStore edit.
 * 5. Clear the journal.
 * 6. Fire FCM effects only after all writes succeed.
 *
 * At startup, [ensureRecovered] checks the journal and either completes the
 * transition (if DataStore URL matches the new URL → save the token) or
 * rolls back (if URL still matches the old URL → restore old token).  Both
 * actions clear the journal.
 */
class SettingsStore internal constructor(
    private val store: DataStore<Preferences>,
    private val secure: SecureStore,
    private val networkGate: NetworkGate? = null,
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
        networkGate = null,
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
        // Insecure HTTP origin approvals (per-endpoint, not per key).
        // Stored as a string-set for future multi-endpoint support, but only one is ever active.
        val APPROVED_HTTP_ORIGINS = stringSetPreferencesKey("approved_http_origins")
        // Cleanup allowance: old endpoints pending a revoke worker.
        // Lives in the SAME DataStore as active approvals so that
        // moveForCleanup is a single atomic edit.
        val CLEANUP_HTTP_ORIGINS = stringSetPreferencesKey("cleanup_http_origins")
    }

    private val purgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val journalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Mutex to prevent concurrent [ensureRecovered] calls from interleaving.
     * Ensures idempotent recovery even under race conditions.
     */
    private val recoveryMutex = Mutex()

    /**
     * Thrown when the journal is corrupted or ambiguous.
     * Prevents credentials from being exposed.
     */
    private class CorruptJournalException : RuntimeException("Corrupted connection-change journal")

    /**
     * The Hermes bearer token lives only in [SecureStore] (Keystore-encrypted).
     * If a legacy plaintext key is found in DataStore it is imported once and
     * the plaintext value is purged asynchronously.
     *
     * Recovery is ensured on the flow boundary before values reach consumers:
     * any [FailClosedException] replaces the settings with an unconfigured state
     * and preserves the journal for the next attempt.
     */
    val settings: Flow<JarvisSettings> = flow {
        try { ensureRecovered() } catch (_: Exception) { emit(JarvisSettings("", "")); return@flow }
        emitAll(store.data.distinctUntilChangedBy { it[Keys.BASE_URL] }.map { p ->
            val legacy = p[Keys.API_KEY]
            val token = secure.importOnce(legacy)
            if (token != null && legacy != null) {
                purgeScope.launch { purgeLegacyKeys() }
            }
            JarvisSettings(
                baseUrl = p[Keys.BASE_URL] ?: "",
                apiKey = token.orEmpty(),
            )
        })
    }

    // ─── Pre-persist validation (defence in depth) ──────────────────────────

    /**
     * Validate [baseUrl] before persisting.  Steps:
     * 1. Trim and check non-empty
     * 2. Parse via [canonicalEndpointIdentity] — throws for malformed, opaque,
     *    unsupported scheme, missing host, userinfo, query, fragment
     * 3. Scheme must be http or https (enforced by [canonicalEndpointIdentity])
     * 4. HTTPS → HTTP downgrade is only allowed when the new origin is already
     *    in the approved set (explicit user consent).
     *
     * @throws InvalidConnectionSettings if the URL is structurally invalid.
     * @throws HttpDowngradeNotAllowed when switching from HTTPS to HTTP
     *   without prior approval.
     * @return the canonical endpoint identity string (used for comparisons).
     *   The **stored** value is the trimmed input, not the canonical form.
     */
    private fun validateConnectionForPersist(
        baseUrl: String,
        oldBaseUrl: String,
        approvedOrigins: Set<String>,
    ): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) throw InvalidConnectionSettings("Empty URL")

        // Step 1-3: structural validation (throws on malformed)
        val canonical = canonicalEndpointIdentity(trimmed)

        // Step 4: HTTPS → HTTP downgrade check (defence in depth).
        // If the old config was HTTPS and the new one is HTTP, we require
        // explicit prior approval to prevent prompt-suppression attacks.
        val oldScheme = oldBaseUrl.trim().lowercase()
        if (oldScheme.startsWith("https://") && !canonical.startsWith("https://")) {
            // Downgrade detected: HTTPS → HTTP.  Only allowed if already approved.
            if (canonical !in approvedOrigins) {
                throw HttpDowngradeNotAllowed()
            }
        }

        // Optional gate-level validation (used in tests with injected gate).
        networkGate?.validate(canonical)

        return canonical
    }

    // ─── Connection change journal (R03) ────────────────────────────────────

    /**
     * Stage a journal entry in [SecureStore] using a synchronous commit.
     *
     * This is called BEFORE any DataStore mutation.  If it fails, the caller
     * must abort and NOT modify any settings.
     *
     * @param oldUrl  Current base URL in DataStore.
     * @param newUrl  The new base URL that will be written.
     * @param operation  KEEP (apiKey=null), CLEAR (apiKey=""), or REPLACE (apiKey="<value>").
     * @param newToken  Present only when operation==REPLACE; the plaintext token to save.
     *
     * @throws JournalWriteFailed if the journal cannot be persisted.
     */
    private fun beginJournal(
        oldUrl: String,
        newUrl: String,
        operation: String,
        newToken: String? = null,
    ) {
        val blob = JournalBlob(
            version = JournalBlob.VERSION,
            oldUrl = oldUrl,
            newUrl = newUrl,
            operation = operation,
            newToken = newToken,
        )
        try {
            secure.saveConnectionChangeJournalSync(JournalBlob.toJson(blob))
        } catch (e: JSONException) {
            throw JournalWriteFailed("Failed to build journal JSON", e)
        }
    }

    /**
     * Clear the connection-change journal from [SecureStore].
     * Best-effort: if it fails, [ensureRecovered] will pick it up next time.
     */
    private fun clearJournal() {
        secure.clearConnectionChangeJournalSync()
    }
    private fun markDataStoreApplied(oldUrl: String, newUrl: String, operation: String, token: String?) {
        beginJournal(oldUrl, newUrl, operation, token) // replace is a confirmed sync commit
        val current = secure.loadConnectionChangeJournal() ?: return
        val parsed = JournalBlob.fromJson(current) ?: return
        secure.saveConnectionChangeJournalSync(JournalBlob.toJson(parsed.copy(phase = "DATASTORE_APPLIED")))
    }

    /**
     * Recover from an interrupted connection change at app startup.
     *
     * Called automatically via [onStart] on the [settings] flow.  Uses a
     * mutex to prevent concurrent invocations.  Idempotent: re-calls after
     * the journal has been cleared are no-ops.
     *
     * Recovery scenarios:
     * - No journal → nothing interrupted.  Silent no-op.
     * - Journal + raw URL == old URL → DataStore write never happened.  Restore
     *   old token if needed (KEEP case), clear journal → Recovered.
     * - Journal + raw URL == new URL → DataStore committed but SecureStore
     *   may not have.  Save the target token, clear journal → Recovered.
     * - Journal + URL differs from both → corrupted state.  Fail-closed:
     *   throw [FailClosedException] and preserve journal.
     *
     * @throws FailClosedException when the journal indicates an inconsistent
     *         state that cannot be auto-recovered.  The journal is preserved
     *         so the user sees no credentials until they re-enter them.
     */
    suspend fun ensureRecovered() {
        recoveryMutex.withLock { recoverJournal() }
    }

    /**
     * Internal journal recovery logic (must be called inside recoveryMutex).
     */
    private suspend fun recoverJournal() {
        val blob = runCatching { secure.loadConnectionChangeJournal() }.getOrNull()
        val parsed = blob?.let { JournalBlob.fromJson(it) } ?: if (blob != null) throw FailClosedException("Corrupt journal") else return

        val oldUrl = parsed.oldUrl
        val newUrl = parsed.newUrl
        val operation = parsed.operation
        val newToken = parsed.newToken

        val currentRaw = store.data.first()[Keys.BASE_URL] ?: ""

        val urlMatchOld = currentRaw == oldUrl
        val urlMatchNew = currentRaw == newUrl

        if (urlMatchOld && !urlMatchNew) {
            // ── DataStore write never happened ────────────────────────
            // Old URL is still present. Roll back: restore old token
            // in KEEP case (journal stored the old token via importOnce).
            if (operation == "KEEP") {
                // The token was not supposed to change; if SecureStore
                // has been cleared (e.g. Keystore reset), re-import it
                // from the DataStore legacy key.
                try {
                    secure.importOnce(store.data.first()[Keys.API_KEY])
                } catch (_: Exception) { /* best-effort */ }
            }
            clearJournal()
        } else if (!urlMatchOld && urlMatchNew) {
            // ── DataStore was updated ─────────────────────────────────
            // Need to ensure SecureStore has the correct token.
            try {
                when (operation) {
                    "KEEP" -> {
                        // Token unchanged at source — already handled by
                        // the fact the old token was never cleared.
                    }
                    "CLEAR" -> {
                    secure.clearTokenSync()
                    }
                    "REPLACE" -> {
                        newToken?.let { secure.saveTokenSync(it) }
                    }
                }
            } catch (e: Exception) {
                // SecureStore unavailable during recovery — fail-closed.
                throw FailClosedException("SecureStore unavailable during recovery: ${e.message}")
            }
            clearJournal()
        } else {
            // ── URL matches both or neither ───────────────────────────
            // Corrupted state: URL is indeterminate. Fail-closed:
            // preserve the journal so credentials are NOT exposed.
            throw FailClosedException("Corrupt journal: url=$currentRaw old=$oldUrl new=$newUrl")
        }
    }

    /**
     * Save connection settings. [apiKey] semantics:
     * - null  → keep whatever token is currently stored;
     * - ""    → clear the stored token;
     * - other → replace the stored token.
     * Any legacy plaintext key in DataStore is removed either way.
     *
     * **Journal protocol** (R03):
     * 1. Read current URL and approvals.
     * 2. Validate URL before any write (throws on invalid input).
     * 3. Stage journal in SecureStore (encrypted, synchronous commit).
     *    If staging fails → abort, previous settings preserved.
     * 4. Single DataStore.edit: write new URL + approvals + legacy purge.
     *    NO SecureStore calls inside this lambda.
     * 5. Write the token to SecureStore outside the DataStore edit.
     * 6. Clear the journal.
     * 7. Fire FCM effects only after all writes succeed.
     *
     * @throws InvalidConnectionSettings if the URL is structurally invalid.
     * @throws HttpDowngradeNotAllowed when downgrading HTTPS → HTTP without approval.
     * @throws JournalWriteFailed if the journal cannot be staged.
     */
    suspend fun updateConnection(baseUrl: String, apiKey: String?) {
        val normalized = baseUrl.trim().trimEnd('/')
        withConnectionLock {
            val old = settings.first()
            val approvedOrigins = approvedHttpOrigins.first()

            // ── 1. Validate BEFORE any persist (defence in depth) ─────
            val canonical = validateConnectionForPersist(
                baseUrl = normalized,
                oldBaseUrl = old.baseUrl,
                approvedOrigins = approvedOrigins,
            )

            // ── 2. Stage: write encrypted journal ────────────────────
            val operation = when (apiKey) {
                null -> JournalBlob.OP_KEEP
                else -> {
                    val trimmed = apiKey.trim()
                    if (trimmed.isEmpty()) JournalBlob.OP_CLEAR else JournalBlob.OP_REPLACE
                }
            }
            val newToken = if (operation == JournalBlob.OP_REPLACE) apiKey?.trim() else null
            beginJournal(old.baseUrl, normalized, operation, newToken)

            // ── 3. Single DataStore edit: URL + approvals + legacy purge ─
            // NO SecureStore calls inside this lambda.
            try {
                store.edit { p ->
                    p[Keys.BASE_URL] = normalized
                    p.remove(Keys.API_KEY)
                    p.remove(Keys.MODEL)

                    // Atomic cleanup of old HTTP origin in the same edit.
                    val oldEndpoint = runCatching {
                        canonicalEndpointIdentity(old.baseUrl.trim())
                    }.getOrNull()
                    if (oldEndpoint != null && oldEndpoint != canonical && old.baseUrl.isNotBlank()) {
                        val currentApproved: Set<String> = p[Keys.APPROVED_HTTP_ORIGINS] ?: emptySet()
                        val currentCleanup: Set<String> = p[Keys.CLEANUP_HTTP_ORIGINS] ?: emptySet()
                        if (oldEndpoint in currentApproved) {
                            p[Keys.APPROVED_HTTP_ORIGINS] = currentApproved - oldEndpoint
                            p[Keys.CLEANUP_HTTP_ORIGINS] = currentCleanup + oldEndpoint
                        }
                    }
                }
                markDataStoreApplied(old.baseUrl, normalized, operation, newToken)

                // ── 4. Write token OUTSIDE the DataStore edit ──────────
                try {
                    when (operation) {
                        JournalBlob.OP_KEEP -> {
                            // Preserve existing token; import legacy if present.
                            secure.importOnce(null)
                        }
                        JournalBlob.OP_CLEAR -> {
                            secure.clearTokenSync()
                        }
                        JournalBlob.OP_REPLACE -> {
                            newToken?.let { secure.saveTokenSync(it) }
                        }
                    }
                } catch (e: Exception) {
                    // Token write failed — DO NOT clear journal.
                    // Recovery will restore/complete on next startup.
                    throw e
                }

                // ── 5. Clear the journal ───────────────────────────────
                clearJournal()

                // ── 6. FCM effects only after all writes succeed ───────
                val newTokenValue = when (apiKey) {
                    null -> old.apiKey
                    else -> apiKey.trim().takeIf { it.isNotEmpty() } ?: old.apiKey
                }
                onConnectionChanged(old, JarvisSettings(normalized, newTokenValue))

            } catch (e: Exception) {
                // DataStore write failed — DO NOT clear journal.
                // The journal preserves enough state for recovery.
                throw e
            }
        }
    }

    // ─── HTTP origin approvals ──────────────────────────────────────────────

    /**
     * Returns the set of HTTP origins that have been explicitly approved.
     * Origins use the normalised URL-only canonical form from
     * [dk.foss.jarvis.hermes.canonicalEndpointIdentity].
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
        }
    }

    companion object
}
