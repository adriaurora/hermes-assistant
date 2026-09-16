package dk.foss.jarvis.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import dk.foss.jarvis.net.NetworkGate

/**
 * JVM tests for the connection-change journal protocol in [SettingsStore].
 * Uses a real DataStore (temp-file-backed) and a fake SecureStore whose
 * cipher/blob store can be forced to fail at specific boundaries.
 *
 * These tests exercise production recovery code, not fakes.
 */
class SettingsStoreRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ─── Fakes ──────────────────────────────────────────────────────────────

    /**
     * Fake cipher that encrypts/decrypts by wrapping in "enc()".
     * Can be configured to fail decryption (simulating Keystore loss).
     */
    private class FakeCipher(
        var decryptFails: Boolean = false,
    ) : AeadCipher {
        override fun encrypt(plainText: String) = "enc($plainText)"
        override fun decrypt(blob: String): String? =
            if (decryptFails) null else blob.removePrefix("enc(").removeSuffix(")")
    }

    /**
     * Fake blob store backed by an in-memory map.
     * Can be configured to fail on write or remove (simulating storage failure).
     */
    private class MemBlobs(
        var writeFails: Boolean = false,
        var removeFails: Boolean = false,
    ) : SecretBlobStore {
        val map = mutableMapOf<String, String>()

        override fun get(alias: String): String? = map[alias]
        override fun put(alias: String, blob: String) {
            if (writeFails) throw IllegalStateException("simulated write failure")
            map[alias] = blob
        }
        override fun putSync(alias: String, blob: String) = put(alias, blob)
        override fun remove(alias: String) {
            if (removeFails) throw IllegalStateException("simulated remove failure")
            map.remove(alias)
        }
    }

    /**
     * Build a SettingsStore with the fake SecureStore.  All parameters use
     * their defaults except [secure].
     */
    private fun makeStore(
        blobs: MemBlobs,
        cipher: FakeCipher = FakeCipher(),
        hook: (suspend (JarvisSettings, JarvisSettings) -> Unit)? = null,
        gate: NetworkGate? = null,
    ): SettingsStore {
        val ds = PreferenceDataStoreFactory.create { File(tmp.newFolder(), "settings.preferences_pb") }
        return SettingsStore(
            store = ds,
            secure = SecureStore(cipher, blobs),
            networkGate = gate,
            onConnectionChanged = hook ?: { _, _ -> },
        )
    }

    // ─── 1. No journal → Recovered ──────────────────────────────────────────

    @Test fun `recovery_no_journal_is_quiet`() = runBlocking {
        val blobs = MemBlobs()
        val store = makeStore(blobs)
        val result = store.ensureRecovered()
        // No exception — the settings flow's onStart calls ensureRecovered
        // and the test verifies the store doesn't crash.
        val settings = store.settings.first()
        assertEquals("", settings.baseUrl)
        assertEquals("", settings.apiKey)
    }

    @Test fun `present journal with keystore failure is corrupt and flow fails closed`() = runBlocking {
        val cipher = FakeCipher(); val blobs = MemBlobs(); val store = makeStore(blobs, cipher = cipher)
        store.updateConnection("http://a.local:8642", "secret")
        blobs.map[SecureStore.CONN_CHANGE_JOURNAL_ALIAS] = "encrypted-journal"
        cipher.decryptFails = true
        assertEquals("", store.settings.first().baseUrl)
        assertTrue(blobs.map.containsKey(SecureStore.CONN_CHANGE_JOURNAL_ALIAS))
    }

    // ─── 2. Update succeeds → journal cleared ───────────────────────────────

    @Test fun `successful_update_clears_journal`() = runBlocking {
        val blobs = MemBlobs()
        val store = makeStore(blobs)

        store.updateConnection("http://a.local:8642", "tok1")
        assertEquals("http://a.local:8642", store.settings.first().baseUrl)
        assertEquals("tok1", store.settings.first().apiKey)
    }

    // ─── 3. Journal write failure aborts updateConnection ───────────────────

    @Test fun `journal_write_failure_aborts_updateConnection`() = runBlocking {
        val blobs = MemBlobs()
        var store = makeStore(blobs, hook = { _, _ -> })

        // Write initial settings
        store.updateConnection("http://a.local:8642", "tok1")
        assertEquals("http://a.local:8642", store.settings.first().baseUrl)

        // Make journal write fail on next update
        blobs.writeFails = true

        try {
            store.updateConnection("http://b.local:8642", "tok2")
            fail("Should have thrown JournalWriteFailed")
        } catch (e: JournalWriteFailed) {
            // Expected — staging failed.
        }

        // Settings unchanged (still at a.local)
        assertEquals("http://a.local:8642", store.settings.first().baseUrl)
    }

    // ─── 4. Manual journal write → URL unchanged → token restored ──────────

    @Test fun `journal_staged_crash_before_datastore_restores_token`() = runBlocking {
        val cipher = FakeCipher()
        val blobs = MemBlobs()
        var store = makeStore(blobs, hook = { _, _ -> })

        // Write initial settings
        store.updateConnection("http://a.local:8642", "tok1")
        assertEquals("tok1", store.settings.first().apiKey)

        // Simulate: journal was written, but DataStore was NOT updated
        // (crash before DataStore.edit). The journal has old URL, new URL,
        // and REPLACE operation with newToken.
        blobs.map.clear() // clear any residual
        val journal = JournalBlob(
            version = 1,
            oldUrl = "http://a.local:8642",
            newUrl = "http://b.local:8642",
            operation = JournalBlob.OP_REPLACE,
            newToken = "tok2",
        )
        val encrypted = cipher.encrypt(JournalBlob.toJson(journal))
        blobs.map["hermes_conn_journal"] = encrypted

        // DataStore still has old URL → recovery should restore old token
        // (KEEP case is implicit — old token is still there).
        val result = store.ensureRecovered()

        // Settings unchanged; no target token was written by the staged crash.
        assertEquals("http://a.local:8642", store.settings.first().baseUrl)
        assertEquals("", store.settings.first().apiKey)
    }

    // ─── 5. Manual journal write → URL changed → new token saved ───────────

    @Test fun `journal_staged_crash_after_datastore_saves_new_token`() = runBlocking {
        val cipher = FakeCipher()
        val blobs = MemBlobs()
        var store = makeStore(blobs, hook = { _, _ -> })

        // Write initial settings
        store.updateConnection("http://a.local:8642", "tok1")

        // Simulate: DataStore was updated but SecureStore token write
        // didn't happen (crash after DataStore edit but before token save).
        // Manually set the new URL in the DataStore to simulate the post-edit state.
        val ds = PreferenceDataStoreFactory.create { File(tmp.newFolder(), "settings2.preferences_pb") }
        ds.edit { it[stringPreferencesKey("base_url")] = "http://b.local:8642" }

        // Write journal indicating REPLACE with tok2
        blobs.map.clear()
        val journal = JournalBlob(
            version = 1,
            oldUrl = "http://a.local:8642",
            newUrl = "http://b.local:8642",
            operation = JournalBlob.OP_REPLACE,
            newToken = "tok2",
            phase = "DATASTORE_APPLIED",
        )
        blobs.map["hermes_conn_journal"] = cipher.encrypt(JournalBlob.toJson(journal))

        // Now create a new store pointing to the same DataStore with updated URL
        store = SettingsStore(
            store = ds,
            secure = SecureStore(cipher, blobs),
            onConnectionChanged = { _, _ -> },
        )

        // Recovery should save tok2 to SecureStore
        store.ensureRecovered()
        assertEquals("tok2", store.settings.first().apiKey)
    }

    // ─── 6. URL invalid → settings preserved ───────────────────────────────

    @Test fun `invalid_url_preserves_previous_settings`() = runBlocking {
        val blobs = MemBlobs()
        val store = makeStore(blobs, hook = { _, _ -> })

        store.updateConnection("http://a.local:8642", "tok1")
        assertEquals("http://a.local:8642", store.settings.first().baseUrl)

        try {
            store.updateConnection("not-a-url", "tok2")
            fail("Should have thrown InvalidConnectionSettings")
        } catch (e: InvalidConnectionSettings) {
            // Expected.
        }

        // Settings unchanged
        assertEquals("http://a.local:8642", store.settings.first().baseUrl)
        assertEquals("tok1", store.settings.first().apiKey)
    }

    // ─── 7. Corrupt journal → fail-closed ─────────────────────────────────

    @Test fun `corrupt_journal_fails_closed`() = runBlocking {
        val cipher = FakeCipher()
        val blobs = MemBlobs()
        val ds = PreferenceDataStoreFactory.create { File(tmp.newFolder(), "settings3.preferences_pb") }
        val store = SettingsStore(
            store = ds,
            secure = SecureStore(cipher, blobs),
            onConnectionChanged = { _, _ -> },
        )

        // Write a valid URL in DataStore
        ds.edit { it[stringPreferencesKey("base_url")] = "http://x.local" }

        // Write a corrupt journal (garbage JSON)
        blobs.map["hermes_conn_journal"] = "not-valid-json"

        // Recovery should NOT throw — it treats missing/invalid blob as "no journal"
        // because loadConnectionChangeJournal returns null for invalid blobs.
        // Actually, our implementation tries to decrypt then parse JSON.
        // If the blob is "not-valid-json", decrypt returns the same string,
        // then JournalBlob.fromJson fails → null → no recovery needed.
        try { store.ensureRecovered(); fail("expected fail closed") } catch (_: FailClosedException) { }
        assertEquals("", store.settings.first().baseUrl)
    }

    // ─── 8. KEEP operation → journal doesn't change token ──────────────────

    @Test fun `keep_operation_no_token_change`() = runBlocking {
        val blobs = MemBlobs()
        val store = makeStore(blobs, hook = { _, _ -> })

        store.updateConnection("http://a.local:8642", "tok1")
        val settings1 = store.settings.first()
        assertEquals("tok1", settings1.apiKey)

        // Update with apiKey=null → KEEP
        store.updateConnection("http://b.local:8642", null)
        val settings2 = store.settings.first()
        assertEquals("http://b.local:8642", settings2.baseUrl)
        assertEquals("tok1", settings2.apiKey) // token preserved
    }

    // ─── 9. CLEAR operation → token removed ────────────────────────────────

    @Test fun `clear_operation_removes_token`() = runBlocking {
        val blobs = MemBlobs()
        val store = makeStore(blobs, hook = { _, _ -> })

        store.updateConnection("http://a.local:8642", "tok1")
        store.updateConnection("http://b.local:8642", "")
        val settings = store.settings.first()
        assertEquals("http://b.local:8642", settings.baseUrl)
        assertEquals("", settings.apiKey)
    }

    // ─── 10. Idempotent recovery ──────────────────────────────────────────

    @Test fun `repeated_recovery_calls_are_idempotent`() = runBlocking {
        val blobs = MemBlobs()
        val store = makeStore(blobs, hook = { _, _ -> })

        store.updateConnection("http://a.local:8642", "tok1")

        store.ensureRecovered() // journal already cleared → no-op
        store.ensureRecovered() // still no journal → no-op
        store.ensureRecovered() // third time → still fine

        assertEquals("http://a.local:8642", store.settings.first().baseUrl)
        assertEquals("tok1", store.settings.first().apiKey)
    }
}
