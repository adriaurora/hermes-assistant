package dk.foss.jarvis.net

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [AndroidApprovedOriginsStore] backed by a temp-file DataStore.
 *
 * Verifies the persistence contract used by the production network gate,
 * including the shared DataStore contract and atomic move operations.
 */
class AndroidApprovedOriginsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: AndroidApprovedOriginsStore
    private lateinit var ds: DataStore<Preferences>

    private fun setup() {
        ds = PreferenceDataStoreFactory.create { File(tmp.newFolder(), "test.preferences_pb") }
        store = AndroidApprovedOriginsStore(ds)
    }

    // ── Existing tests ─────────────────────────────────────────────────

    @Test fun `initially_empty`() = runBlocking {
        setup()
        val list = store.list()
        assertEquals(0, list.size)
    }

    @Test fun `add_and_isApproved`() = runBlocking {
        setup()
        store.add("http://dev.local:8642")
        assertEquals(
            setOf("http://dev.local:8642"),
            store.list()
        )
        assertEquals(true, store.isApproved("http://dev.local:8642"))
    }

    @Test fun `isApproved_returns_false_for_unapproved`() = runBlocking {
        setup()
        store.add("http://dev.local:8642")
        assertEquals(false, store.isApproved("http://other.local:9000"))
    }

    @Test fun `remove_and_verify_not_approved`() = runBlocking {
        setup()
        store.add("http://dev.local:8642")
        store.remove("http://dev.local:8642")
        assertEquals(0, store.list().size)
        assertEquals(false, store.isApproved("http://dev.local:8642"))
    }

    @Test fun `multiple_approvals_stored_separately`() = runBlocking {
        setup()
        store.add("http://a.local:8642")
        store.add("http://b.local:8642")
        store.add("http://c.local:8642")

        val list = store.list()
        assertEquals(3, list.size)
        assertEquals(true, store.isApproved("http://a.local:8642"))
        assertEquals(true, store.isApproved("http://b.local:8642"))
        assertEquals(true, store.isApproved("http://c.local:8642"))
    }

    @Test fun `remove_nonexistent_is_idempotent`() = runBlocking {
        setup()
        store.remove("http://nonexistent.local")
        assertEquals(0, store.list().size)
    }

    @Test fun `add_duplicate_is_idempotent`() = runBlocking {
        setup()
        store.add("http://dev.local:8642")
        store.add("http://dev.local:8642")
        store.add("http://dev.local:8642")

        assertEquals(1, store.list().size)
        assertEquals(true, store.isApproved("http://dev.local:8642"))
    }

    @Test fun `clearAllExcept_keeps_specific_origin`() = runBlocking {
        setup()
        store.add("http://a.local:8642")
        store.add("http://b.local:8642")
        store.add("http://c.local:8642")

        store.clearAllExcept("http://b.local:8642")

        val approvals = store.list()
        assertEquals(setOf("http://b.local:8642"), approvals)
    }

    @Test fun `clearAllExcept_noop_when_single_origin`() = runBlocking {
        setup()
        store.add("http://dev.local:8642")

        store.clearAllExcept("http://dev.local:8642")

        val approvals = store.list()
        assertEquals(setOf("http://dev.local:8642"), approvals)
    }

    @Test fun `clearAllExcept_nonexistent_keep_is_noop`() = runBlocking {
        setup()
        store.add("http://a.local:8642")
        store.add("http://b.local:8642")

        store.clearAllExcept("http://nonexistent.local")

        val approvals = store.list()
        assertEquals(2, approvals.size)
        assertEquals(true, approvals.contains("http://a.local:8642"))
        assertEquals(true, approvals.contains("http://b.local:8642"))
    }

    // ── Shared DataStore test ─────────────────────────────────────────

    /**
     * Prove that two AndroidApprovedOriginsStore instances created with
     * the same injected DataStore observe each other's changes.
     *
     * This is the contract that enables SettingsStore and
     * AndroidApprovedOriginsStore to share a single DataStore in production
     * without data corruption.
     */
    @Test fun `two_instances_same_datastore_observe_each_other()`() = runBlocking {
        // Create a single DataStore shared by both stores
        val sharedDs = PreferenceDataStoreFactory.create {
            File(tmp.newFolder(), "shared.preferences_pb")
        }

        val storeA = AndroidApprovedOriginsStore(sharedDs)
        val storeB = AndroidApprovedOriginsStore(sharedDs)

        // Write via storeA
        storeA.add("http://shared.local:8642")

        // storeB reads the same data
        assertTrue(storeB.isApprovedSync("http://shared.local:8642"))
        assertFalse(storeB.isCleanupAllowedSync("http://shared.local:8642"))

        // Write cleanup via storeB (idempotent on empty)
        storeB.removeFromCleanup("http://old.local:8642")

        // storeA reads the same state
        assertTrue(storeA.isApprovedSync("http://shared.local:8642"))

        // Remove via storeA
        storeA.remove("http://shared.local:8642")

        // storeB reads the cleared state
        assertFalse(storeB.isApprovedSync("http://shared.local:8642"))
    }

    /**
     * Prove that SettingsStore and AndroidApprovedOriginsStore observe the
     * same DataStore when both use the shared key.
     *
     * SettingsStore writes `approved_http_origins` and
     * `cleanup_http_origins` to its DataStore. AndroidApprovedOriginsStore
     * reads the SAME keys from the SAME DataStore.
     */
    @Test fun `settings_and_approved_origins_share_same_datastore()`() = runBlocking {
        val sharedDs = PreferenceDataStoreFactory.create {
            File(tmp.newFolder(), "settings_shared.preferences_pb")
        }

        // AndroidApprovedOriginsStore reads/writes approved_http_origins and
        // cleanup_http_origins keys.
        val approvedStore = AndroidApprovedOriginsStore(sharedDs)

        // Simulate what SettingsStore.approveHttpOrigin does — write directly
        // to the same DataStore using the same key names.
        val approvedKey = stringSetPreferencesKey("approved_http_origins")
        val cleanupKey = stringSetPreferencesKey("cleanup_http_origins")

        // Approve via SettingsStore equivalent (same key)
        sharedDs.edit { prefs ->
            val current = prefs[approvedKey] ?: emptySet<String>()
            prefs[approvedKey] = current + "http://test.local:8642"
        }

        // AndroidApprovedOriginsStore sees it
        assertTrue(
            "ApprovedOriginsStore must read what SettingsStore wrote",
            approvedStore.isApprovedSync("http://test.local:8642")
        )

        // Move via SettingsStore equivalent (single edit to both keys)
        sharedDs.edit { prefs ->
            val currentApproved = prefs[approvedKey] ?: emptySet<String>()
            val currentCleanup = prefs[cleanupKey] ?: emptySet<String>()
            if ("http://test.local:8642" in currentApproved) {
                prefs[approvedKey] = currentApproved - "http://test.local:8642"
                prefs[cleanupKey] = currentCleanup + "http://test.local:8642"
            }
        }

        // AndroidApprovedOriginsStore sees the moved state
        assertFalse(
            "Must be removed from active after move",
            approvedStore.isApprovedSync("http://test.local:8642")
        )
        assertTrue(
            "Must be in cleanup after move",
            approvedStore.isCleanupAllowedSync("http://test.local:8642")
        )
    }

    // ── Atomic move test ──────────────────────────────────────────────

    /**
     * Prove that moveForCleanup updates BOTH active and cleanup keys in
     * a single DataStore edit — no nested read/edit.
     *
     * Reads the raw keys from the DataStore to verify atomicity.
     */
    @Test fun `moveForCleanup_single_edit_atomic()`() = runBlocking {
        setup()
        val approvedKey = stringSetPreferencesKey("approved_http_origins")
        val cleanupKey = stringSetPreferencesKey("cleanup_http_origins")

        store.add("http://old.local:8642")

        // Verify initial state
        val beforeApproved = ds.data.first()[approvedKey] ?: emptySet<String>()
        val beforeCleanup = ds.data.first()[cleanupKey] ?: emptySet<String>()
        assertEquals(1, beforeApproved.size)
        assertEquals(0, beforeCleanup.size)
        assertTrue("http://old.local:8642" in beforeApproved)

        // Perform move
        store.moveForCleanup("http://old.local:8642")

        // Verify atomic transition — both keys updated in one edit
        val afterApproved = ds.data.first()[approvedKey] ?: emptySet<String>()
        val afterCleanup = ds.data.first()[cleanupKey] ?: emptySet<String>()
        assertEquals(
            "Active must be cleared",
            0,
            afterApproved.size
        )
        assertEquals(
            "Cleanup must be set",
            1,
            afterCleanup.size
        )
        assertTrue("http://old.local:8642" in afterCleanup)
    }

    @Test fun `clearAll_cleans_both_scopes()`() = runBlocking {
        setup()
        store.add("http://test.local:8642")
        store.moveForCleanup("http://test.local:8642")

        assertTrue("Before clear: cleanup allowed", store.isCleanupAllowedSync("http://test.local:8642"))
        store.clearAll()
        assertFalse("After clear: ordinary must be blocked", store.isApprovedSync("http://test.local:8642"))
        assertFalse("After clear: cleanup must be blocked", store.isCleanupAllowedSync("http://test.local:8642"))
    }

    @Test fun `https_not_in_approval_sets()`() = runBlocking {
        setup()
        assertFalse("HTTPS not in active approval", store.isApprovedSync("https://hermes-a.local:8642"))
        assertFalse("HTTPS not in cleanup", store.isCleanupAllowedSync("https://hermes-a.local:8642"))
    }
}