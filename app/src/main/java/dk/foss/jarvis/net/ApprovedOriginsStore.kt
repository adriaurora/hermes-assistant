package dk.foss.jarvis.net

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Persistent storage for HTTP origins that the user has explicitly approved.
 *
 * An "origin" is the normalised form produced by
 * [dk.foss.jarvis.hermes.originIdentity]: scheme + lowercase host +
 * (explicit default port) + path (no trailing slash).
 *
 * The production implementation ([AndroidApprovedOriginsStore]) uses the same
 * DataStore file and key as [dk.foss.jarvis.data.SettingsStore] so there is a
 * single source of truth: `jarvis_settings` → `approved_http_origins` key.
 * The in-memory variant is used in JVM tests.
 *
 * ## Scope separation
 *
 * Origins live in one of two scopes:
 *
 * 1. **Active approval** (ordinary traffic) — the endpoint the user has
 *    explicitly configured and approved.  Checked by [validate] for regular
 *    HTTP calls.
 * 2. **Cleanup allowance** (revoke only) — an origin that was the active
 *    endpoint but is now pending a revoke worker.  Only checked by
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

    /** Remove all approved origins. */
    suspend fun clearAll()

    /** Remove all approved origins except [keep]. */
    suspend fun clearAllExcept(keep: String)

    // ── Cleanup scope operations ──────────────────────────────────────

    /**
     * Atomic move: remove [origin] from active approval and add it to
     * the cleanup allowance.  Called by [SettingsStore] when the user
     * changes the active endpoint — the old origin must stop being
     * reachable for ordinary traffic while the revoke worker is pending.
     *
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

    // Cleanup allowance set — separate from active approval.
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

// ─── Shared DataStore extension (same as SettingsStore uses) ──────────────────

private val Context.jarvisDataStore by preferencesDataStore(name = "jarvis_settings")

/**
 * Android-backed implementation backed by a Preferences DataStore.
 *
 * Uses the same DataStore file (`jarvis_settings`) and key
 * (`approved_http_origins`) as [dk.foss.jarvis.data.SettingsStore] so that
 * there is exactly one durable source of truth for active approvals.  The
 * key name matches the one in SettingsStore.Keys.APPROVED_HTTP_ORIGINS.
 *
 * Cleanup origins live in a separate DataStore key (`cleanup_http_origins`)
 * within the same file, so that a failure during the write does not
 * corrupt the active approval set.
 *
 * The synchronous check falls back to the suspending version because
 * DataStore access is inherently asynchronous.  Production code always
 * uses the suspending [isApproved] through the gate's [validate] path,
 * which is called from inside a suspending coroutine context.
 */
class AndroidApprovedOriginsStore(
    private val store: DataStore<Preferences>,
    val cleanupStore: DataStore<Preferences>? = null,
) : ApprovedOriginsStore {
    constructor(context: Context) : this(
        store = context.applicationContext.jarvisDataStore,
        cleanupStore = context.applicationContext.jarvisCleanupDataStore,
    )

    private companion object {
        // MUST match SettingsStore.Keys.APPROVED_HTTP_ORIGINS exactly.
        val KEY = stringSetPreferencesKey("approved_http_origins")
        // Separate key for cleanup allowance origins.
        val CLEANUP_KEY = stringSetPreferencesKey("cleanup_http_origins")
    }

    override suspend fun list(): Set<String> = store.data.map { it[KEY] ?: emptySet() }.first()

    override fun cleanupOriginsFlow(): Flow<Set<String>> = cleanupStore?.data?.map { it[CLEANUP_KEY] ?: emptySet() }
        ?: kotlinx.coroutines.flow.emptyFlow()

    override suspend fun add(origin: String) {
        store.edit { prefs ->
            val current = prefs[KEY] ?: emptySet<String>()
            prefs[KEY] = current + origin
        }
    }

    override suspend fun remove(origin: String) {
        store.edit { prefs ->
            val current = prefs[KEY] ?: emptySet<String>()
            prefs[KEY] = current - origin
        }
    }

    override suspend fun clearAll() {
        store.edit { prefs -> prefs.remove(KEY) }
        cleanupStore?.edit { prefs -> prefs.remove(CLEANUP_KEY) }
    }

    override suspend fun clearAllExcept(keep: String) {
        store.edit { prefs ->
            val current = prefs[KEY] ?: emptySet<String>()
            if (keep in current && current.size > 1) {
                prefs[KEY] = setOf(keep)
            }
        }
    }

    // ── Cleanup scope ────────────────────────────────────────────────

    override suspend fun moveForCleanup(origin: String) {
        store.edit { prefs ->
            val current = prefs[KEY] ?: emptySet<String>()
            if (origin in current) {
                prefs[KEY] = current - origin
                val cleanupCurrent = cleanupStore?.data?.map { it[CLEANUP_KEY] ?: emptySet() }?.first() ?: emptySet()
                cleanupStore?.edit { cleanup ->
                    cleanup[CLEANUP_KEY] = cleanupCurrent + origin
                }
            }
        }
    }

    override suspend fun removeFromCleanup(origin: String) {
        cleanupStore?.edit { prefs ->
            val current = prefs[CLEANUP_KEY] ?: emptySet<String>()
            if (origin in current) {
                prefs[CLEANUP_KEY] = current - origin
            }
        }
    }

    override suspend fun isCleanupAllowed(origin: String): Boolean {
        cleanupStore?.let { s ->
            return origin in s.data.map { it[CLEANUP_KEY] ?: emptySet() }.first()
        }
        return false
    }

    override fun isCleanupAllowedSync(origin: String): Boolean {
        return try {
            cleanupStore?.let { s ->
                runBlocking { s.data.map { it[CLEANUP_KEY] ?: emptySet() }.first() }.contains(origin)
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun isApproved(origin: String): Boolean = origin in list()

    /**
     * Synchronous check: for the Android store this checks if the origin
     * is in the current approved set.  Since the gate's validate() is
     * called from inside a suspending coroutine (all callers are suspend),
     * this can safely use runBlocking to synchronise DataStore reads.
     *
     * For the in-memory store, isApprovedSync reads from a mutableSet directly.
     */
    override fun isApprovedSync(origin: String): Boolean {
        return try {
            runBlocking { list() }.contains(origin)
        } catch (_: Exception) {
            false
        }
    }
}

/** Extension to create the cleanup DataStore on the same file. */
private val Context.jarvisCleanupDataStore by preferencesDataStore(name = "jarvis_settings")