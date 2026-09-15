package dk.foss.jarvis.net

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Persistent storage for HTTP origins that the user has explicitly approved.
 *
 * An "origin" is the normalised form produced by
 * [dk.foss.jarvis.hermes.originIdentity]: scheme + lowercase host +
 * (explicit default port) + path (no trailing slash).
 *
 * The production implementation ([AndroidApprovedOriginsStore]) uses exactly
 * ONE DataStore instance (file `jarvis_settings`) that is shared with
 * [dk.foss.jarvis.data.SettingsStore].  Active and cleanup keys live in the
 * same DataStore so that [moveForCleanup] is a single atomic edit — no
 * nested DataStore read/edit.
 *
 * ## Scope separation
 *
 * Origins live in one of two scopes within the same DataStore:
 *
 * 1. **Active approval** (`approved_http_origins`) — the endpoint the user
 *    has explicitly configured and approved.  Checked by [validate] for
 *    regular HTTP calls.
 * 2. **Cleanup allowance** (`cleanup_http_origins`) — an origin that was the
 *    active endpoint but is now pending a revoke worker.  Checked by
 *    [validateForCleanup] so that revoke calls can still reach the old
 *    endpoint after it's been revoked from active.
 *
 * The atomic transition from active → cleanup is [moveForCleanup].
 */
interface ApprovedOriginsStore {
    /** Returns the current set of active approved origin strings. */
    suspend fun list(): Set<String>

    /** Returns a Flow of the current cleanup-allowance origin strings. */
    fun cleanupOriginsFlow(): Flow<Set<String>>

    /** Add [origin] to the active approved set (idempotent). */
    suspend fun add(origin: String)

    /** Remove [origin] from the active approved set (idempotent). */
    suspend fun remove(origin: String)

    /** Returns `true` if [origin] is in the active approved set. */
    suspend fun isApproved(origin: String): Boolean

    /** Synchronous approval check for use from non-suspending contexts. */
    fun isApprovedSync(origin: String): Boolean

    /** Remove all approved origins (active + cleanup). */
    suspend fun clearAll()

    /** Remove all approved origins except [keep] (active only). */
    suspend fun clearAllExcept(keep: String)

    // ── Cleanup scope operations ──────────────────────────────────────

    /**
     * Atomic move: remove [origin] from active approval and add it to
     * the cleanup allowance.  Called by [SettingsStore] when the user
     * changes the active endpoint — the old origin must stop being
     * reachable for ordinary traffic while the revoke worker is pending.
     *
     * Implemented as a single [edit] so both keys are updated atomically.
     * Idempotent: if [origin] is not in active approval it is silently
     * ignored.  If it's already in cleanup allowance nothing happens.
     */
    suspend fun moveForCleanup(origin: String)

    /**
     * Remove [origin] from the cleanup allowance (used when revoke
     * completes or when the user manually clears cleanup origins).
     */
    suspend fun removeFromCleanup(origin: String)

    /** Returns `true` if [origin] is in the cleanup allowance. */
    suspend fun isCleanupAllowed(origin: String): Boolean

    /** Synchronous check if [origin] is in the cleanup allowance. */
    fun isCleanupAllowedSync(origin: String): Boolean

    companion object {
        /**
         * Convenience: check if a URL string uses HTTP (before normalisation).
         * Returns `false` if the URL is not HTTP (HTTPS is always allowed).
         */
        fun String.isHttpOrigin(): Boolean = runCatching {
            val uri = java.net.URI.create(this.trim())
            uri.scheme?.lowercase() == "http"
        }.getOrDefault(false)
    }
}

/**
 * In-memory implementation for JVM tests.
 *
 * All methods are effectively synchronous because they read from a
 * mutableSet, but they are declared `suspend` to match the interface.
 */
class InMemoryApprovedOriginsStore : ApprovedOriginsStore {
    private val set = mutableSetOf<String>()
    private val cleanupSet = mutableSetOf<String>()

    // Synchronous access for tests
    fun addSync(origin: String) { set.add(origin) }
    fun contains(origin: String): Boolean = origin in set
    fun clearAllSync() { set.clear() }

    override suspend fun list(): Set<String> = set.toSet()

    override fun cleanupOriginsFlow(): Flow<Set<String>> = kotlinx.coroutines.flow.MutableStateFlow(
        cleanupSet.toSet(),
    ).also { it.value = cleanupSet.toSet() }

    override suspend fun add(origin: String) { set.add(origin) }
    override suspend fun remove(origin: String) { set.remove(origin) }
    override suspend fun isApproved(origin: String): Boolean = origin in set
    override suspend fun clearAll() { set.clear(); cleanupSet.clear() }
    override suspend fun clearAllExcept(keep: String) {
        if (keep in set && set.size > 1) {
            set.removeAll { it != keep }
        }
    }

    // ── Cleanup scope ────────────────────────────────────────────────

    override suspend fun moveForCleanup(origin: String) {
        if (set.remove(origin)) {
            cleanupSet.add(origin)
        }
    }

    override suspend fun removeFromCleanup(origin: String) {
        cleanupSet.remove(origin)
    }

    override suspend fun isCleanupAllowed(origin: String): Boolean = origin in cleanupSet
    override fun isCleanupAllowedSync(origin: String): Boolean = origin in cleanupSet
    override fun isApprovedSync(origin: String): Boolean = origin in set
}

/**
 * Android-backed implementation backed by a single Preferences DataStore.
 *
 * Uses exactly ONE DataStore instance (file `jarvis_settings`) shared with
 * [dk.foss.jarvis.data.SettingsStore].  Active (`approved_http_origins`) and
 * cleanup (`cleanup_http_origins`) keys live in the same DataStore so that
 * [moveForCleanup] is a single atomic edit — no nested DataStore read/edit.
 *
 * The synchronous check falls back to the suspending version because
 * DataStore access is inherently asynchronous.  Production code always
 * uses the suspending [isApproved] through the gate's [validate] path,
 * which is called from inside a suspending coroutine context.
 *
 * @param store The single DataStore instance.  For production use, prefer
 *   the [Context] constructor which creates a shared singleton instance
 *   accessible from both [SettingsStore] and other [AndroidApprovedOriginsStore].
 */
class AndroidApprovedOriginsStore(
    private val store: DataStore<Preferences>,
) : ApprovedOriginsStore {
    constructor(context: Context) : this(store = dataStorePreferences(context))

    companion object {
        /**
         * Exactly one DataStore instance for the `jarvis_settings` file.
         * Lazily initialised and cached — safe to call from multiple threads.
         */
        @Volatile private var _instance: DataStore<Preferences>? = null

/**
         * Returns the shared singleton DataStore for `jarvis_settings`.
         * Uses the AndroidX `preferencesDataStoreFile` canonical path so that
         * SettingsStore and AndroidApprovedOriginsStore share exactly the same
         * underlying file — no race, no double-write.
         */
        @Synchronized
        fun dataStorePreferences(context: Context): DataStore<Preferences> {
            _instance?.let { return it }
            val appCtx = context.applicationContext
            return PreferenceDataStoreFactory.create(
                produceFile = { appCtx.preferencesDataStoreFile("jarvis_settings") }
            ).also { _instance = it }
        }

        // MUST match SettingsStore.Keys.APPROVED_HTTP_ORIGINS exactly.
        private val ACTIVE_KEY = stringSetPreferencesKey("approved_http_origins")
        private val CLEANUP_KEY = stringSetPreferencesKey("cleanup_http_origins")
    }

    override suspend fun list(): Set<String> = store.data.map { it[ACTIVE_KEY] ?: emptySet() }.first()

    override fun cleanupOriginsFlow(): Flow<Set<String>> = store.data.map {
        it[CLEANUP_KEY] ?: emptySet()
    }

    override suspend fun add(origin: String) {
        store.edit { prefs ->
            val current = prefs[ACTIVE_KEY] ?: emptySet<String>()
            prefs[ACTIVE_KEY] = current + origin
        }
    }

    override suspend fun remove(origin: String) {
        store.edit { prefs ->
            val current = prefs[ACTIVE_KEY] ?: emptySet<String>()
            prefs[ACTIVE_KEY] = current - origin
        }
    }

    override suspend fun clearAll() {
        store.edit { prefs ->
            prefs.remove(ACTIVE_KEY)
            prefs.remove(CLEANUP_KEY)
        }
    }

    override suspend fun clearAllExcept(keep: String) {
        store.edit { prefs ->
            val current = prefs[ACTIVE_KEY] ?: emptySet<String>()
            if (keep in current && current.size > 1) {
                prefs[ACTIVE_KEY] = setOf(keep)
            }
        }
    }

    // ── Cleanup scope (single-edit atomic transition) ─────────────────

    /**
     * Atomic move within a single DataStore edit — removes from active,
     * adds to cleanup.  No nested DataStore reads or edits.
     */
    override suspend fun moveForCleanup(origin: String) {
        store.edit { prefs ->
            val current = prefs[ACTIVE_KEY] ?: emptySet<String>()
            val cleanupCurrent = prefs[CLEANUP_KEY] ?: emptySet<String>()
            if (origin in current) {
                prefs[ACTIVE_KEY] = current - origin
                prefs[CLEANUP_KEY] = cleanupCurrent + origin
            }
        }
    }

    override suspend fun removeFromCleanup(origin: String) {
        store.edit { prefs ->
            val current = prefs[CLEANUP_KEY] ?: emptySet<String>()
            if (origin in current) {
                prefs[CLEANUP_KEY] = current - origin
            }
        }
    }

    override suspend fun isCleanupAllowed(origin: String): Boolean {
        return origin in store.data.map { it[CLEANUP_KEY] ?: emptySet() }.first()
    }

    override fun isCleanupAllowedSync(origin: String): Boolean {
        return try {
            runBlocking { store.data.map { it[CLEANUP_KEY] ?: emptySet() }.first() }.contains(origin)
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun isApproved(origin: String): Boolean = origin in list()

    /**
     * Synchronous check: uses runBlocking because the gate's validate() is
     * called from inside a suspending coroutine (all callers are suspend),
     * so we can safely synchronise DataStore reads.
     */
    override fun isApprovedSync(origin: String): Boolean {
        return try {
            runBlocking { list() }.contains(origin)
        } catch (_: Exception) {
            false
        }
    }
}