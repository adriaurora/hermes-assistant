package dk.foss.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Añade casos de device_secret al SecureStoreTest existente.
 * Copia los fakes del test original para no depender de Android.
 */
class SecureStoreTestDeviceSecret {

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

    @Test fun `device_secret roundtrip`() {
        val (store, _) = newStore()
        assertNull(store.loadDeviceSecret())
        store.saveDeviceSecret("secret-abc")
        assertEquals("secret-abc", store.loadDeviceSecret())
    }

    @Test fun `saveDeviceSecret y loadDeviceSecret`() {
        val (store, blobs) = newStore()
        store.saveDeviceSecret("my-device-secret")
        assertEquals("my-device-secret", store.loadDeviceSecret())
    }

    @Test fun `clearDeviceSecret elimina el secreto`() {
        val (store, blobs) = newStore()
        store.saveDeviceSecret("to-clear")
        store.clearDeviceSecret()
        assertNull(store.loadDeviceSecret())
        assertNull(blobs.map[SecureStore.DEVICE_SECRET_ALIAS])
    }

    @Test fun `blob almacenado no es plaintext`() {
        val (store, blobs) = newStore()
        store.saveDeviceSecret("hunter2")
        val stored = blobs.map[SecureStore.DEVICE_SECRET_ALIAS]!!
        assertNotEquals("hunter2", stored)
        assertTrue(stored.isNotEmpty())
    }
}