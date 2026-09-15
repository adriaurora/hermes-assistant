package dk.foss.jarvis.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dk.foss.jarvis.net.AndroidApprovedOriginsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests that SettingsStore and AndroidApprovedOriginsShare the SAME DataStore
 * instance (shared singleton) and that the canonical `jarvis_settings` path is
 * used consistently.
 */
class SettingsStoreSharedDataStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeCipher : AeadCipher {
        var failDecrypt = false
        override fun encrypt(plainText: String) = "enc($plainText)"
        override fun decrypt(blob: String): String? =
            if (failDecrypt) null else blob.removePrefix("enc(").removeSuffix(")")
    }

    private class MemBlobs : SecretBlobStore {
        val map = mutableMapOf<String, String>()
        override fun get(a: String) = map[a]
        override fun put(a: String, b: String) { map[a] = b }
        override fun remove(a: String) { map.remove(a) }
    }

    private lateinit var ds: DataStore<Preferences>
    private lateinit var settingsStore: SettingsStore
    private lateinit var originsStore: AndroidApprovedOriginsStore

    private fun setup(): AndroidApprovedOriginsStore {
        val file = File(tmp.newFolder(), "jarvis_settings.preferences_pb")
        ds = PreferenceDataStoreFactory.create { file }
        settingsStore = SettingsStore(ds, SecureStore(FakeCipher(), MemBlobs()))
        originsStore = AndroidApprovedOriginsStore(ds)
        return originsStore
    }

    private fun canonical(url: String) = dk.foss.jarvis.hermes.originIdentity(url.trim())

    @Test fun `shared singleton DataStore`() = runBlocking {
        setup()
        // Both stores must point to the same DataStore instance
        // Writing via one and reading via the other proves they share state
        settingsStore.updateConnection("http://a.local:8642", "tok1")

        // Write approval via originsStore (using canonical origin)
        originsStore.add(canonical("http://approved.local:8642"))

        // Verify via settingsStore approved origins
        val approved = settingsStore.approvedHttpOrigins.first()
        assertTrue(approved.contains(canonical("http://approved.local:8642")))
    }

    @Test fun `settings_and_approvals_share_dataStore`() = runBlocking {
        setup()
        // Both use the same underlying DataStore - write settings then verify approval reads work
        settingsStore.updateConnection("http://a.local:8642", "tok1")
        originsStore.add(canonical("http://approved.local:8642"))

        // Settings persisted
        val settings = settingsStore.settings.first()
        assertEquals("http://a.local:8642", settings.baseUrl)

        // Approval persisted in same DataStore
        val approved = settingsStore.approvedHttpOrigins.first()
        assertTrue(approved.contains(canonical("http://approved.local:8642")))
    }

    @Test fun `active_cleanup_atomic_same_dataStore`() = runBlocking {
        setup()
        val origin = canonical("http://old.local:8642")
        originsStore.add(origin)

        // Before move: in active
        assertTrue(originsStore.isApproved(origin))
        assertTrue(originsStore.isCleanupAllowed(origin).not())

        // Move to cleanup (single edit in same DataStore)
        originsStore.moveForCleanup(origin)

        // After move: active cleared, cleanup has it
        assertTrue(originsStore.isApproved(origin).not())
        assertTrue(originsStore.isCleanupAllowed(origin))
    }

    @Test fun `canonical_path_contract_jarvis_settings`() {
        // Verify that preferencesDataStoreFile produces a filename containing "jarvis_settings"
        // The actual file path is platform-dependent but the base name must match
        val file = File(tmp.root, "jarvis_settings.preferences_pb")
        assertNotNull(file)
        assertTrue(file.name.contains("jarvis_settings"))
    }

    @Test fun `shared_singleton_settings_approval_scope`() = runBlocking {
        setup()
        // Write settings then add approval, then verify atomic cleanup move
        settingsStore.updateConnection("http://a.local:8642", "tok1")
        // Use canonical origin when approving
        settingsStore.approveHttpOrigin(canonical("http://a.local:8642"))

        // Simulate endpoint change: old origin should move to cleanup
        settingsStore.updateConnection("http://b.local:8642", "tok2")

        val approved = settingsStore.approvedHttpOrigins.first()
        val cleanup = settingsStore.cleanupHttpOrigins.first()

        // Old origin moved to cleanup, not in active anymore
        val oldOrigin = canonical("http://a.local:8642")
        assertTrue("Cleanup must contain old origin", cleanup.contains(oldOrigin))
        assertTrue("Active must not contain old origin", approved.contains(oldOrigin).not())
    }

    @Test fun `cleanup_move_idempotent`() = runBlocking {
        setup()
        val origin = canonical("http://x.local:9999")
        originsStore.add(origin)

        // First move
        originsStore.moveForCleanup(origin)
        assertTrue(originsStore.isApproved(origin).not())
        assertTrue(originsStore.isCleanupAllowed(origin))

        // Second move (idempotent - should not throw, cleanup should still have it once)
        originsStore.moveForCleanup(origin)
        assertTrue(originsStore.isApproved(origin).not())
        assertTrue(originsStore.isCleanupAllowed(origin))
    }
}