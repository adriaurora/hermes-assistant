package dk.foss.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the SecureStore contract with an in-memory cipher/blob store, so
 * the storage/migration logic is verified without Android Keystore. The real
 * [KeystoreAeadCipher] additionally guarantees ciphertext-at-rest.
 */
class SecureStoreTest {

    /** Fake AEAD: "ciphertext" is reversible but visibly not plaintext. */
    private class FakeCipher : AeadCipher {
        override fun encrypt(plainText: String): String =
            plainText.reversed().map { it.code + 1 }.joinToString(",")

        override fun decrypt(blob: String): String? = runCatching {
            blob.split(",").map { (it.toInt() - 1).toChar() }.reversed().joinToString("")
        }.getOrNull()
    }

    private class MemBlobs : SecretBlobStore {
        val map = mutableMapOf<String, String>()
        override fun get(alias: String): String? = map[alias]
        override fun put(alias: String, blob: String) { map[alias] = blob }
        override fun remove(alias: String) { map.remove(alias) }
    }

    private fun newStore(blobs: MemBlobs = MemBlobs()) =
        SecureStore(FakeCipher(), blobs) to blobs

    @Test
    fun `roundtrip save and load`() {
        val (store, _) = newStore()
        assertNull(store.loadToken())
        store.saveToken("hunter2")
        assertEquals("hunter2", store.loadToken())
    }

    @Test
    fun `plaintext never lands in the blob store`() {
        val (store, blobs) = newStore()
        store.saveToken("hunter2")
        val stored = blobs.map[SecureStore.TOKEN_ALIAS]!!
        assertNotEquals("hunter2", stored)
        assertTrue(stored.isNotEmpty())
    }

    @Test
    fun `clear removes the token`() {
        val (store, blobs) = newStore()
        store.saveToken("hunter2")
        store.clearToken()
        assertNull(store.loadToken())
        assertTrue(blobs.map.isEmpty())
    }

    @Test
    fun `importOnce imports a legacy plaintext exactly once`() {
        val (store, _) = newStore()
        assertEquals("legacy-secret", store.importOnce("legacy-secret"))
        // Second call with a DIFFERENT legacy value must not overwrite.
        assertEquals("legacy-secret", store.importOnce("other"))
        // Blank/null legacy values are ignored when a token exists.
        assertEquals("legacy-secret", store.importOnce(null))
    }

    @Test
    fun `importOnce with no legacy stays empty`() {
        val (store, _) = newStore()
        assertNull(store.importOnce(null))
        assertNull(store.importOnce(""))
        assertNull(store.loadToken())
    }

    @Test
    fun `undecryptable blob reads as absent`() {
        val blobs = MemBlobs()
        val (store, _) = newStore(blobs)
        blobs.put(SecureStore.TOKEN_ALIAS, "not-a-valid-blob")
        assertNull(store.loadToken())
    }
}
