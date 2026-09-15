package dk.foss.jarvis.push

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dk.foss.jarvis.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for FcmRevokeWorker.canonicalHttpCleanupOrigin — the internal helper
 * that derives the cleanup key from a raw hermesOrigin.
 *
 * Each test follows the same flow:
 * 1. Approve canonical origin A
 * 2. Call SettingsStore.updateConnection(B) so A moves from active → cleanup
 * 3. Assert cleanup contains canonical A
 * 4. Call FcmRevokeCleanup.onComplete with FcmRevokeWorker.canonicalHttpCleanupOrigin(rawA)
 * 5. Assert cleanup is empty
 */
class FcmRevokeWorkerCleanupOriginTest {

    private class FakeCipher : AeadCipher {
        override fun encrypt(p: String) = "enc($p)"
        override fun decrypt(b: String) = b.removePrefix("enc(").removeSuffix(")")
    }
    private class MemBlobs : SecretBlobStore {
        val map = mutableMapOf<String, String>()
        override fun get(a: String) = map[a]
        override fun put(a: String, b: String) { map[a] = b }
        override fun remove(a: String) { map.remove(a) }
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeRegistry(): Pair<DeviceRegistryStore, SecureStore> {
        val secure = SecureStore(FakeCipher(), MemBlobs())
        return Pair(DeviceRegistryStore(secure), secure)
    }

    private fun makeSettingsStore(): SettingsStore {
        val ds = PreferenceDataStoreFactory.create { File(tmp.newFolder(), "settings.preferences_pb") }
        return SettingsStore(ds, SecureStore(FakeCipher(), MemBlobs()))
    }

    private fun makePushState(): PushPrefs {
        val ds = PreferenceDataStoreFactory.create { File(tmp.newFolder(), "p.preferences_pb") }
        return PushPrefs(ds)
    }

    /**
     * Helper: approve canonical A, switch connection to B (moving A → cleanup),
     * assert cleanup has A, then call onComplete with the derived key and
     * assert cleanup is empty.
     */
    private fun testCleanupWith(
        rawA: String,
        canonicalA: String,
        B: String,
        expectedDerived: String?,
    ) = runBlocking {
        val settingsStore = makeSettingsStore()
        val (registry, secure) = makeRegistry()
        val prefs = makePushState()

        // Step 1: set initial base URL and approve canonical A
        settingsStore.updateConnection(rawA, "tok1")
        settingsStore.approveHttpOrigin(canonicalA)

        // Step 2: switch to B — moves A from approved → cleanup
        settingsStore.updateConnection(B, "tok2")

        // Step 3: assert cleanup contains canonical A
        val cleanupBefore = settingsStore.cleanupHttpOrigins.first()
        assertTrue("Cleanup must contain canonical A ($canonicalA)", cleanupBefore.contains(canonicalA))
        assertEquals("Approved should not contain A", 0, settingsStore.approvedHttpOrigins.first().size)

        // Step 4: derive via worker helper and complete
        val derived = FcmRevokeWorker.canonicalHttpCleanupOrigin(rawA)
        assertEquals("Derived key must match expected", expectedDerived, derived)

        if (expectedDerived != null) {
            FcmRevokeCleanup.onComplete(registry, secure, prefs, settingsStore, derived) { }
        } else {
            FcmRevokeCleanup.onComplete(registry, secure, prefs, settingsStore, null) { }
        }

        // Step 5: assert cleanup is empty
        val cleanupAfter = settingsStore.cleanupHttpOrigins.first()
        assertEquals("Cleanup must be empty after onComplete", emptySet<String>(), cleanupAfter)
    }

    // ─── required raw A cases ─────────────────────────────────────────────

    /** HTTP://Hermes.Local:80 → canonical http://hermes.local/ */
    @Test fun `raw_HTTP_uppercase_scheme_folded_to_lowercase`() = testCleanupWith(
        rawA = "HTTP://Hermes.Local:80",
        canonicalA = "http://hermes.local/",
        B = "https://hermes.local/",
        expectedDerived = "http://hermes.local/",
    )

    /** http://hermes.local:8642/ → canonical http://hermes.local:8642/ */
    @Test fun `raw_non_default_port_preserved`() = testCleanupWith(
        rawA = "http://hermes.local:8642/",
        canonicalA = "http://hermes.local:8642/",
        B = "https://hermes.local/",
        expectedDerived = "http://hermes.local:8642/",
    )

    // ─── malformed raw ────────────────────────────────────────────────────

    /** Malformed raw yields null; cleanup is NOT touched. */
    @Test fun `malformed_raw_yields_null_no_cleanup_deletion`() = runBlocking {
        val settingsStore = makeSettingsStore()
        val (registry, secure) = makeRegistry()
        val prefs = makePushState()

        // Set up: approve canonical A, move it to cleanup
        settingsStore.updateConnection("http://hermes.local/", "tok1")
        settingsStore.approveHttpOrigin("http://hermes.local/")
        settingsStore.updateConnection("https://hermes.local/", "tok2")

        val cleanupBefore = settingsStore.cleanupHttpOrigins.first()
        assertTrue("Cleanup must contain origin before malformed call", cleanupBefore.contains("http://hermes.local/"))

        // Malformed input → null
        val derived = FcmRevokeWorker.canonicalHttpCleanupOrigin("not-a-url")
        assertNull("Malformed input should yield null", derived)

        // onComplete with null does NOT remove from cleanup
        FcmRevokeCleanup.onComplete(registry, secure, prefs, settingsStore, null) { }

        val cleanupAfter = settingsStore.cleanupHttpOrigins.first()
        assertEquals("Malformed should not have deleted from cleanup", 1, cleanupAfter.size)
        assertTrue("Cleanup should still contain original", cleanupAfter.contains("http://hermes.local/"))
    }
}
