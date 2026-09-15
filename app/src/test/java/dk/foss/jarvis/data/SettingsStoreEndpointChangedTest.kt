package dk.foss.jarvis.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import dk.foss.jarvis.hermes.originIdentity

/**
 * Tests that SettingsStore endpoint change uses URL-only canonical endpoint
 * identities — not raw URL or API key — for tracking old origins in cleanup.
 *
 * Key contracts:
 * - Same canonical URL (case, default port, trailing slash) does NOT trigger cleanup
 * - Different host/port/path DOES trigger cleanup
 * - HTTP→HTTPS change triggers cleanup for old HTTP origin
 * - Cleanup scope is never used for ordinary RPC/Sessions traffic
 */
class SettingsStoreEndpointChangedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeCipher : AeadCipher {
        override fun encrypt(plainText: String) = "enc($plainText)"
        override fun decrypt(blob: String): String? = blob.removePrefix("enc(").removeSuffix(")")
    }

    private class MemBlobs : SecretBlobStore {
        val map = mutableMapOf<String, String>()
        override fun get(a: String) = map[a]
        override fun put(a: String, b: String) { map[a] = b }
        override fun remove(a: String) { map.remove(a) }
    }

    private fun makeStore(): SettingsStore {
        val ds = PreferenceDataStoreFactory.create { File(tmp.newFolder(), "settings.preferences_pb") }
        return SettingsStore(ds, SecureStore(FakeCipher(), MemBlobs()))
    }

    private fun canon(url: String) = originIdentity(url.trim())

    // ── Canonical-equivalent: no cleanup triggered ───────────────────────

    @Test fun `trailing_slash_difference_does_not_move_cleanup`() = runBlocking {
        val store = makeStore()
        val origin = canon("http://hermes.local/api")
        store.updateConnection("http://hermes.local/api", "tok1")
        store.approveHttpOrigin(origin)

        store.updateConnection("http://hermes.local/api/", "tok2") // trailing slash only

        // Old origin NOT moved to cleanup because it's the same canonical URL
        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()
        assertTrue("Active should still have the origin", approved.contains(origin))
        assertTrue("Cleanup should be empty", cleanup.isEmpty())
    }

    @Test fun `default_port_fold_does_not_move_cleanup`() = runBlocking {
        val store = makeStore()
        val origin = canon("http://hermes.local")
        store.updateConnection("http://hermes.local", "tok1")
        store.approveHttpOrigin(origin)

        store.updateConnection("http://hermes.local:80", "tok2") // explicit default port

        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()
        assertTrue("Active should still have the origin", approved.contains(origin))
        assertTrue("Cleanup should be empty", cleanup.isEmpty())
    }

    @Test fun `host_case_difference_does_not_move_cleanup`() = runBlocking {
        val store = makeStore()
        val origin = canon("http://hermes.local/api")
        store.updateConnection("http://HERMES.local/api", "tok1")
        store.approveHttpOrigin(origin)

        store.updateConnection("http://hermes.local/api", "tok2") // lowercase

        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()
        assertTrue("Active should still have the origin", approved.contains(origin))
        assertTrue("Cleanup should be empty", cleanup.isEmpty())
    }

    @Test fun `api_key_rotation_does_not_move_cleanup`() = runBlocking {
        val store = makeStore()
        val origin = canon("http://hermes.local/api")
        store.updateConnection("http://hermes.local/api", "key-a")
        store.approveHttpOrigin(origin)

        store.updateConnection("http://hermes.local/api", "key-b") // only key changed

        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()
        assertTrue("Active should still have the origin", approved.contains(origin))
        assertTrue("Cleanup should be empty", cleanup.isEmpty())
    }

    // ── Real changes: cleanup triggered ─────────────────────────────────

    @Test fun `different_host_moves_old_to_cleanup`() = runBlocking {
        val store = makeStore()
        val oldOrigin = canon("http://hermes-a.local/api")
        store.updateConnection("http://hermes-a.local/api", "tok1")
        store.approveHttpOrigin(oldOrigin)

        store.updateConnection("http://hermes-b.local/api", "tok2")

        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()

        assertTrue("Old origin NOT in active", approved.contains(oldOrigin).not())
        assertTrue("Old origin in cleanup", cleanup.contains(oldOrigin))
    }

    @Test fun `different_port_moves_old_to_cleanup`() = runBlocking {
        val store = makeStore()
        val oldOrigin = canon("http://hermes.local:8642")
        store.updateConnection("http://hermes.local:8642", "tok1")
        store.approveHttpOrigin(oldOrigin)

        store.updateConnection("http://hermes.local:9999", "tok2")

        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()

        assertTrue("Old origin NOT in active", approved.contains(oldOrigin).not())
        assertTrue("Old origin in cleanup", cleanup.contains(oldOrigin))
    }

    @Test fun `different_path_moves_old_to_cleanup`() = runBlocking {
        val store = makeStore()
        val oldOrigin = canon("http://hermes.local/api/v1")
        store.updateConnection("http://hermes.local/api/v1", "tok1")
        store.approveHttpOrigin(oldOrigin)

        store.updateConnection("http://hermes.local/api/v2", "tok2")

        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()

        assertTrue("Old origin NOT in active", approved.contains(oldOrigin).not())
        assertTrue("Old origin in cleanup", cleanup.contains(oldOrigin))
    }

    @Test fun `http_to_https_moves_old_to_cleanup`() = runBlocking {
        val store = makeStore()
        val oldOrigin = canon("http://hermes.local/api")
        store.updateConnection("http://hermes.local/api", "tok1")
        store.approveHttpOrigin(oldOrigin)

        store.updateConnection("https://hermes.local/api", "tok2") // scheme change

        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()

        assertTrue("Old origin NOT in active", approved.contains(oldOrigin).not())
        assertTrue("Old origin in cleanup", cleanup.contains(oldOrigin))
    }

    // ── device.revoke and manual revoke completion ──────────────────────

    @Test fun `revokeHttpOrigin_clears_both_scopes`() = runBlocking {
        val store = makeStore()
        val origin = canon("http://hermes.local:8642")
        store.approveHttpOrigin(origin)
        store.moveForCleanup(origin)

        // In cleanup
        val cleanupBefore = store.cleanupHttpOrigins.first()
        assertTrue("Before revoke: in cleanup", cleanupBefore.contains(origin))

        store.revokeHttpOrigin(origin)

        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()
        assertTrue("After revoke: NOT in active", approved.contains(origin).not())
        assertTrue("After revoke: NOT in cleanup", cleanup.contains(origin).not())
    }

    @Test fun `manual_cleanup_revoke_completion`() = runBlocking {
        val store = makeStore()
        val origin = canon("http://hermes.local:8642")
        store.approveHttpOrigin(origin)
        store.moveForCleanup(origin)

        store.removeFromCleanup(origin)

        val cleanup = store.cleanupHttpOrigins.first()
        assertTrue("After removeFromCleanup: empty", cleanup.isEmpty())
    }

    // ── Cleanup scope never used for ordinary RPC/Sessions ──────────────

    @Test fun `cleanup_scope_isolated_from_approval`() = runBlocking {
        val store = makeStore()
        val origin = canon("http://hermes.local:8642")
        store.approveHttpOrigin(origin)

        // Move to cleanup — only cleanup scope, not active
        store.moveForCleanup(origin)

        // Active should be empty, cleanup should have it
        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()

        assertTrue("Active must be empty after move", approved.isEmpty())
        assertTrue("Cleanup must have the origin", cleanup.contains(origin))
    }

    @Test fun `cleanup_never_mixed_with_approval`() = runBlocking {
        val store = makeStore()
        val origin1 = canon("http://a.local:8642")
        val origin2 = canon("http://b.local:8642")

        store.approveHttpOrigin(origin1)
        store.approveHttpOrigin(origin2)
        store.moveForCleanup(origin1)

        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()

        // Only origin2 should be in approved
        assertTrue("Approved should have origin2", approved.contains(origin2))
        assertTrue("Approved should NOT have origin1", approved.contains(origin1).not())
        // Only origin1 should be in cleanup
        assertTrue("Cleanup should have origin1", cleanup.contains(origin1))
        assertTrue("Cleanup should NOT have origin2", cleanup.contains(origin2).not())
    }
}