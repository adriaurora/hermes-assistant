package dk.foss.jarvis.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM test for the plaintext-key migration and connection-update semantics,
 * using a real (temp-file-backed) Preferences DataStore and a fake cipher +
 * in-memory blob store behind a real [SecureStore].
 */
class SettingsStoreMigrationTest {

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
        override fun get(alias: String): String? = map[alias]
        override fun put(alias: String, blob: String) { map[alias] = blob }
        override fun remove(alias: String) { map.remove(alias) }
    }

    private val cipher = FakeCipher()
    private val blobs = MemBlobs()
    private lateinit var ds: DataStore<Preferences>
    private lateinit var store: SettingsStore
    private val legacyKey = stringPreferencesKey("api_key")

    private fun newStore(): SettingsStore {
        ds = PreferenceDataStoreFactory.create { File(tmp.newFolder(), "test.preferences_pb") }
        store = SettingsStore(ds, SecureStore(cipher, blobs))
        return store
    }

    private fun secureBlob(): String? = blobs.map[SecureStore.TOKEN_ALIAS]

    @Test
    fun `legacy plaintext key is imported encrypted and purged`() = runBlocking {
        newStore()
        ds.edit { it[legacyKey] = "super-secret" }

        val settings = store.settings.first()
        assertEquals("super-secret", settings.apiKey)
        // Stored blob is ciphertext (fake-cipher wrapped), never the plaintext.
        assertEquals("enc(super-secret)", secureBlob())

        // The async purge must remove the plaintext value from DataStore.
        waitUntil { runBlocking { ds.data.first().get(legacyKey) } == null }
        assertNull(ds.data.first().get(legacyKey))
    }

    @Test
    fun `updateConnection normalizes url and manages token lifecycle`() = runBlocking {
        newStore()

        store.updateConnection("  http://vm.local:8642/ ", "tok1")
        var s = store.settings.first()
        assertEquals("http://vm.local:8642", s.baseUrl)
        assertEquals("tok1", s.apiKey)

        // null keeps the stored token.
        store.updateConnection("http://other:8642", null)
        s = store.settings.first()
        assertEquals("http://other:8642", s.baseUrl)
        assertEquals("tok1", s.apiKey)

        // "" clears the token entirely.
        store.updateConnection("http://other:8642", "")
        s = store.settings.first()
        assertEquals("", s.apiKey)
        assertNull(secureBlob())
    }

    @Test
    fun `undecryptable blob reads as not configured`() = runBlocking {
        newStore()
        store.updateConnection("http://vm:8642", "k1")
        cipher.failDecrypt = true // e.g. Keystore key lost on a backup restore
        val s = store.settings.first()
        assertEquals("", s.apiKey)
        assertEquals(false, s.isConfigured)
    }

    private fun waitUntil(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > timeoutMs) throw AssertionError("timeout")
            Thread.sleep(20)
        }
    }
}
