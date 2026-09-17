package dk.foss.jarvis.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import dk.foss.jarvis.hermes.canonicalEndpointIdentity
import dk.foss.jarvis.net.InMemoryApprovedOriginsStore
import dk.foss.jarvis.net.NetworkGate

/**
 * Tests for [SettingsStore] pre-persist validation and invalidation semantics.
 *
 * Key contracts:
 * - Structurally invalid URLs (empty, missing host, opaque, unsupported scheme,
 *   userinfo, query, fragment) throw and **never** replace previous settings
 * - HTTPS → HTTP downgrade without approval throws and preserves previous
 * - FCM effects only fire after atomic commit
 * - URL-equivalent forms (case, default port, trailing slash) do NOT trigger
 *   cleanup moves (verified via [canonicalEndpointIdentity])
 */
class SettingsStoreValidationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeCipher : AeadCipher {
        override fun encrypt(plainText: String) = "enc($plainText)"
        override fun decrypt(blob: String): String? =
            if (blob.startsWith("enc(")) blob.removePrefix("enc(").removeSuffix(")") else null
    }

    private class MemBlobs : SecretBlobStore {
        val map = mutableMapOf<String, String>()
        override fun get(alias: String): String? = map[alias]
        override fun put(alias: String, blob: String) { map[alias] = blob }
        override fun remove(alias: String) { map.remove(alias) }
    }

    private fun makeStore(
        gate: NetworkGate? = null,
        hook: (suspend (JarvisSettings, JarvisSettings) -> Unit)? = null,
    ): SettingsStore {
        val ds = PreferenceDataStoreFactory.create { File(tmp.newFolder(), "settings.preferences_pb") }
        return SettingsStore(
            store = ds,
            secure = SecureStore(FakeCipher(), MemBlobs()),
            networkGate = gate,
            onConnectionChanged = hook ?: { _, _ -> },
        )
    }

    private fun canon(url: String) = canonicalEndpointIdentity(url.trim())

    // ── Malformed URL: no commit, previous survives ─────────────────────

    @Test fun `empty_url_does_not_commit_previous_survives`() = runBlocking {
        val store = makeStore()
        store.updateConnection("https://good.local", "key1")

        val before = store.settings.first()
        assertEquals("https://good.local", before.baseUrl)
        assertEquals("key1", before.apiKey)

        // Empty URL — should throw
        val emptyEx = try { store.updateConnection("", "key2"); null } catch (e: InvalidConnectionSettings) { e }
        val blankEx = try { store.updateConnection("  ", "key2"); null } catch (e: InvalidConnectionSettings) { e }
        assertNotNull(emptyEx)
        assertNotNull(blankEx)

        // Previous settings preserved
        val after = store.settings.first()
        assertEquals("https://good.local", after.baseUrl)
        assertEquals("key1", after.apiKey)
    }

    @Test fun `missing_host_throws_and_previous_survives`() = runBlocking {
        val store = makeStore()
        store.updateConnection("https://good.local", "key1")

        val before = store.settings.first()

        // "http:" has scheme but no host
        val ex = try { store.updateConnection("http:", "key2"); null } catch (e: IllegalArgumentException) { e }
        assertNotNull(ex)

        val after = store.settings.first()
        assertEquals(before.baseUrl, after.baseUrl)
    }

    @Test fun `opaque_uri_throws_and_previous_survives`() = runBlocking {
        val store = makeStore()
        store.updateConnection("https://good.local", "key1")

        val before = store.settings.first()

        val ex = try { store.updateConnection("http:evil.local", "key2"); null } catch (e: IllegalArgumentException) { e }
        assertNotNull(ex)

        val after = store.settings.first()
        assertEquals(before.baseUrl, after.baseUrl)
    }

    @Test fun `unsupported_scheme_throws_and_previous_survives`() = runBlocking {
        val store = makeStore()
        store.updateConnection("https://good.local", "key1")

        val before = store.settings.first()

        // ftp:// is unsupported by canonicalEndpointIdentity
        val ex = try { store.updateConnection("ftp://evil.local", "key2"); null } catch (e: IllegalArgumentException) { e }
        assertNotNull(ex)

        val after = store.settings.first()
        assertEquals(before.baseUrl, after.baseUrl)
    }

    @Test fun `userinfo_throws_and_previous_survives`() = runBlocking {
        val store = makeStore()
        store.updateConnection("https://good.local", "key1")

        val before = store.settings.first()

        val ex = try { store.updateConnection("https://user:pass@good.local", "key2"); null } catch (e: IllegalArgumentException) { e }
        assertNotNull(ex)

        val after = store.settings.first()
        assertEquals(before.baseUrl, after.baseUrl)
    }

    @Test fun `query_string_throws_and_previous_survives`() = runBlocking {
        val store = makeStore()
        store.updateConnection("https://good.local", "key1")

        val before = store.settings.first()

        val ex = try { store.updateConnection("https://good.local?debug=true", "key2"); null } catch (e: IllegalArgumentException) { e }
        assertNotNull(ex)

        val after = store.settings.first()
        assertEquals(before.baseUrl, after.baseUrl)
    }

    @Test fun `fragment_throws_and_previous_survives`() = runBlocking {
        val store = makeStore()
        store.updateConnection("https://good.local", "key1")

        val before = store.settings.first()

        val ex = try { store.updateConnection("https://good.local#section", "key2"); null } catch (e: IllegalArgumentException) { e }
        assertNotNull(ex)

        val after = store.settings.first()
        assertEquals(before.baseUrl, after.baseUrl)
    }

    // ── HTTPS → HTTP downgrade: no approval, throws, previous survives ──

    @Test fun `https_to_http_no_approval_throws_previous_survives`() = runBlocking {
        val store = makeStore()
        store.updateConnection("https://good.local", "key1")

        val before = store.settings.first()
        assertEquals("https://good.local", before.baseUrl)

        // HTTPS → HTTP without approval → throws
        val ex = try { store.updateConnection("http://good.local", "key2"); null } catch (e: HttpDowngradeNotAllowed) { e }
        assertNotNull(ex)

        // Previous HTTPS settings preserved
        val after = store.settings.first()
        assertEquals("https://good.local", after.baseUrl)
        assertEquals("key1", after.apiKey)
    }

    @Test fun `https_to_http_with_approval_permits_change`() = runBlocking {
        val store = makeStore()
        store.updateConnection("https://good.local", "key1")

        // First approve the HTTP origin
        store.approveHttpOrigin(canonicalEndpointIdentity("http://good.local"))

        // Now the downgrade is allowed (user has explicitly approved)
        store.updateConnection("http://good.local", "key2")

        val after = store.settings.first()
        assertEquals("http://good.local", after.baseUrl)
        assertEquals("key2", after.apiKey)
    }

    @Test fun `http_to_http_change_allowed_without_extra_approval`() = runBlocking {
        val store = makeStore()
        store.updateConnection("http://old.local", "key1")

        val before = store.settings.first()
        assertEquals("http://old.local", before.baseUrl)

        // Same scheme (HTTP → HTTP) — no downgrade check needed
        store.updateConnection("http://new.local", "key2")

        val after = store.settings.first()
        assertEquals("http://new.local", after.baseUrl)
        assertEquals("key2", after.apiKey)
    }

    @Test fun `https_to_https_change_allowed`() = runBlocking {
        val store = makeStore()
        store.updateConnection("https://old.local", "key1")

        store.updateConnection("https://new.local", "key2")

        val after = store.settings.first()
        assertEquals("https://new.local", after.baseUrl)
    }

    // ── URL-equivalent forms: no unnecessary cleanup move ───────────────

    @Test fun `trailing_slash_equivalent_no_cleanup_move`() = runBlocking {
        val store = makeStore()
        val origin = canon("http://good.local/api")
        store.updateConnection("http://good.local/api", "key1")
        store.approveHttpOrigin(origin)

        // Save with trailing slash — canonical form is the same
        store.updateConnection("http://good.local/api/", "key2")

        // Approved should still contain the origin (no cleanup move)
        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()
        assertTrue("Active still has origin", approved.contains(origin))
        assertTrue("Cleanup is empty", cleanup.isEmpty())
    }

    @Test fun `default_port_equivalent_no_cleanup_move`() = runBlocking {
        val store = makeStore()
        val origin = canon("http://good.local")
        store.updateConnection("http://good.local", "key1")
        store.approveHttpOrigin(origin)

        // Save with explicit port 80 — canonical form is the same
        store.updateConnection("http://good.local:80", "key2")

        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()
        assertTrue("Active still has origin", approved.contains(origin))
        assertTrue("Cleanup is empty", cleanup.isEmpty())
    }

    @Test fun `host_case_equivalent_no_cleanup_move`() = runBlocking {
        val store = makeStore()
        val origin = canon("http://good.local/api")
        store.updateConnection("http://GOOD.LOCAL/api", "key1")
        store.approveHttpOrigin(origin)

        // Save with lowercase — canonical form is the same
        store.updateConnection("http://good.local/api", "key2")

        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()
        assertTrue("Active still has origin", approved.contains(origin))
        assertTrue("Cleanup is empty", cleanup.isEmpty())
    }

    // ── API key changes do not trigger cleanup ──────────────────────────

    @Test fun `api_key_only_change_does_not_move_approval`() = runBlocking {
        val store = makeStore()
        val origin = canon("http://good.local/api")
        store.updateConnection("http://good.local/api", "key1")
        store.approveHttpOrigin(origin)

        store.updateConnection("http://good.local/api", "key2")

        val approved = store.approvedHttpOrigins.first()
        val cleanup = store.cleanupHttpOrigins.first()
        assertTrue("Active still has origin", approved.contains(origin))
        assertTrue("Cleanup is empty", cleanup.isEmpty())
    }

    // ── FCM effects only after commit ───────────────────────────────────

    @Test fun `fcm_effects_only_fire_after_successful_commit`() = runBlocking {
        var effectsFired = false
        var oldSettings: JarvisSettings? = null
        var newSettings: JarvisSettings? = null

        val store = makeStore { old, new ->
            oldSettings = old
            newSettings = new
            effectsFired = true
        }

        store.updateConnection("https://good.local", "key1")
        assertTrue("Effects fired after first commit", effectsFired)
        // First call: old settings are empty (no previous data)
        assertEquals("", oldSettings?.baseUrl)
        assertEquals("key1", newSettings?.apiKey)

        // Invalid URL — effects should NOT fire
        effectsFired = false
        oldSettings = null
        newSettings = null

        runCatching { store.updateConnection("", "key2") }

        assertFalse("Effects did NOT fire after failed commit", effectsFired)
        assertNull("Old settings not recorded on failure", oldSettings)
        assertNull("New settings not recorded on failure", newSettings)
    }

    @Test fun `downgrade_exception_no_fcm_effects`() = runBlocking {
        var effectsFired = false

        val store = makeStore { _, _ -> effectsFired = true }

        store.updateConnection("https://good.local", "key1")
        effectsFired = false

        runCatching { store.updateConnection("http://evil.local", "key2") }

        assertFalse("FCM effects did NOT fire on downgrade rejection", effectsFired)
    }

    // ── Network gate integration (injected gate) ────────────────────────

    @Test fun `network_gate_rejection_prevents_commit`() = runBlocking {
        val activeStore = InMemoryApprovedOriginsStore()
        val gate = NetworkGate(activeStore, activeStore)
        // No approval added — gate will reject any HTTP

        val store = makeStore(gate = gate)

        // HTTP without approval — gate throws → no commit
        val ex = try { store.updateConnection("http://unapproved.local", "key1"); null }
            catch (e: dk.foss.jarvis.net.BlockedRequest) { e }
        assertNotNull(ex)

        // Empty settings (first call)
        val settings = store.settings.first()
        assertEquals("", settings.baseUrl)
        assertEquals("", settings.apiKey)
    }

    @Test fun `network_gate_approved_http_commits`() = runBlocking {
        val activeStore = InMemoryApprovedOriginsStore()
        activeStore.addSync(canon("http://approved.local"))
        val gate = NetworkGate(activeStore, activeStore)

        val store = makeStore(gate = gate)

        store.updateConnection("http://approved.local", "key1")
        val settings = store.settings.first()
        assertEquals("http://approved.local", settings.baseUrl)
        assertEquals("key1", settings.apiKey)
    }
}