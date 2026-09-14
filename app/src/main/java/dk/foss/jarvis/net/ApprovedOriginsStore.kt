package dk.foss.jarvis.net

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
 */
interface ApprovedOriginsStore {
    /** Returns the current set of approved origin strings. */
    suspend fun list(): Set<String>

    /** Add [origin] to the approved set (idempotent). */
    suspend fun add(origin: String)

    /** Remove [origin] from the approved set (idempotent). */
    suspend fun remove(origin: String)

    /** Returns `true` if [origin] is in the approved set. */
    suspend fun isApproved(origin: String): Boolean

    /** Synchronous approval check for use from non-suspending contexts. */
    fun isApprovedSync(origin: String): Boolean

    /** Remove all approved origins. */
    suspend fun clearAll()

    /** Remove all approved origins except [keep]. */
    suspend fun clearAllExcept(keep: String)

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

    // Synchronous access for tests
    fun addSync(origin: String) { set.add(origin) }
    fun contains(origin: String): Boolean = origin in set
    fun clearAllSync() { set.clear() }

    override suspend fun list(): Set<String> = set.toSet()
    override suspend fun add(origin: String) { set.add(origin) }
    override suspend fun remove(origin: String) { set.remove(origin) }
    override suspend fun isApproved(origin: String): Boolean = origin in set
    override suspend fun clearAll() { set.clear() }
    override suspend fun clearAllExcept(keep: String) {
        if (keep in set && set.size > 1) {
            // Remove all origins except the one being kept.
            set.removeAll { it != keep }
        }
    }
    override fun isApprovedSync(origin: String): Boolean = origin in set
}

// ─── Shared DataStore extension (same as SettingsStore uses) ──────────────────

private val Context.jarvisDataStore by preferencesDataStore(name = "jarvis_settings")

/**
 * Android-backed implementation backed by a Preferences DataStore.
 *
 * Uses the same DataStore file (`jarvis_settings`) and key
 * (`approved_http_origins`) as [dk.foss.jarvis.data.SettingsStore] so that
 * there is exactly one durable source of truth.  The key name matches the
 * one in SettingsStore.Keys.APPROVED_HTTP_ORIGINS.
 *
 * The synchronous check falls back to the suspending version because
 * DataStore access is inherently asynchronous.  Production code always
 * uses the suspending [isApproved] through the gate's [validate] path,
 * which is called from inside a suspending coroutine context.
 */
class AndroidApprovedOriginsStore(private val store: DataStore<Preferences>) : ApprovedOriginsStore {
    constructor(context: Context) : this(context.applicationContext.jarvisDataStore)

    private companion object {
        // MUST match SettingsStore.Keys.APPROVED_HTTP_ORIGINS exactly.
        val KEY = stringSetPreferencesKey("approved_http_origins")
    }

    override suspend fun list(): Set<String> = store.data.map { it[KEY] ?: emptySet() }.first()

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
    }

    override suspend fun clearAllExcept(keep: String) {
        store.edit { prefs ->
            val current = prefs[KEY] ?: emptySet<String>()
            if (keep in current && current.size > 1) {
                // Keep only the specified origin; remove all others.
                prefs[KEY] = setOf(keep)
            }
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