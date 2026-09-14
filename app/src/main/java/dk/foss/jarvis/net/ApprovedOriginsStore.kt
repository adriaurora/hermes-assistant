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
 * The production implementation backs onto Android DataStore; the in-memory
 * variant is used in JVM tests.
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
    override fun isApprovedSync(origin: String): Boolean = origin in set
}

// ─── Android implementation ──────────────────────────────────────────────────

private val Context.approvedOriginsDataStore by preferencesDataStore(name = "approved_origins")

/**
 * Android-backed implementation backed by a Preferences DataStore.
 * Stored as a string-set under the key "approved_origins".
 *
 * The synchronous check falls back to the suspending version because
 * DataStore access is inherently asynchronous.  Production code always
 * uses the suspending [isApproved] through the gate's [validate] path,
 * which is called from inside a suspending coroutine context.
 */
class AndroidApprovedOriginsStore(private val store: DataStore<Preferences>) : ApprovedOriginsStore {
    constructor(context: Context) : this(context.applicationContext.approvedOriginsDataStore)

    private companion object {
        val KEY = stringSetPreferencesKey("approved_origins")
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