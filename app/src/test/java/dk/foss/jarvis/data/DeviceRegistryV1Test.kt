package dk.foss.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de DeviceRegistryStore usando SecureStore(FakeCipher, MemBlobs).
 * El constructor internal(secure: SecureStore) permite testear sin Android.
 */
class DeviceRegistryV1Test {

    private class FakeCipher : AeadCipher {
        override fun encrypt(plainText: String): String =
            plainText.reversed().map { it.code + 1 }.joinToString(",")
        override fun decrypt(blob: String): String? = runCatching {
            blob.split(",").map { (it.toInt() - 1).toChar() }.reversed().joinToString("")
        }.getOrNull()
    }

    private class MemBlobs : SecretBlobStore {
        val map = mutableMapOf<String, String>()
        val putOrder = mutableListOf<String>()
        override fun get(alias: String): String? = map[alias]
        override fun put(alias: String, blob: String) {
            putOrder.add(alias)
            map[alias] = blob
        }
        override fun remove(alias: String) { map.remove(alias) }
    }

    private fun newStore(blobs: MemBlobs = MemBlobs()) =
        DeviceRegistryStore(SecureStore(FakeCipher(), blobs)) to blobs

    @Test fun `saveV1 registra orden secret ANTES que id`() {
        val (store, blobs) = newStore()
        store.saveV1("dev-1", "secret-1", "push-endpoint", "hermes-origin", "api-key-1")
        val order = blobs.putOrder
        val secretIdx = order.indexOf("hermes_device_secret")
        val idIdx = order.indexOf("hermes_device_id")
        assertTrue("hermes_device_secret debe escribirse ANTES que hermes_device_id", secretIdx < idIdx)
    }

    @Test fun `saveV1 carga completo con deviceSecret`() {
        val (store, _) = newStore()
        store.saveV1("dev-1", "secret-1", "push-endpoint", "hermes-origin", "api-key-1")
        val reg = store.load()
        assertNotNull(reg)
        assertEquals("dev-1", reg!!.deviceId)
        assertEquals("push-endpoint", reg.pushEndpoint)
        assertEquals("hermes-origin", reg.hermesOrigin)
        assertEquals("api-key-1", reg.apiKey)
        assertEquals("secret-1", reg.deviceSecret)
    }

    @Test fun `save clásico (legacy) no rompe`() {
        val (store, _) = newStore()
        store.save("dev-1", "push-endpoint", "hermes-origin", "api-key-1")
        val reg = store.load()
        assertNotNull(reg)
        assertEquals("dev-1", reg!!.deviceId)
        assertEquals("push-endpoint", reg.pushEndpoint)
        assertEquals("hermes-origin", reg.hermesOrigin)
        assertEquals("api-key-1", reg.apiKey)
        // deviceSecret null porque se usó save() no saveV1()
        assertNull(reg.deviceSecret)
    }

    @Test fun `saveV1 sobre enrollment existente sobreescribe`() {
        val (store, _) = newStore()
        store.saveV1("dev-1", "secret-old", "push-old", "origin-old", "key-old")
        store.saveV1("dev-2", "secret-new", "push-new", "origin-new", "key-new")
        val reg = store.load()
        assertNotNull(reg)
        assertEquals("dev-2", reg!!.deviceId)
        assertEquals("secret-new", reg.deviceSecret)
        assertEquals("push-new", reg.pushEndpoint)
        assertEquals("origin-new", reg.hermesOrigin)
        assertEquals("key-new", reg.apiKey)
    }

    @Test fun `clear() elimina secret`() {
        val (store, blobs) = newStore()
        store.saveV1("dev-1", "secret-1", "push-endpoint", "hermes-origin", "api-key-1")
        store.clear()
        assertNull(store.load())
        assertTrue(blobs.map.isEmpty())
    }

    @Test fun `toString REDACTED sin secretos`() {
        val reg = DeviceRegistration(
            deviceId = "D", pushEndpoint = "T",
            hermesOrigin = "O", apiKey = "APIKEY-1", deviceSecret = "SECRET-1"
        )
        val s = reg.toString()
        assertTrue(s.contains("REDACTED"))
        assertTrue(s.contains("deviceId=D"))
        assertTrue(s.contains("pushEndpoint=T"))
        assertTrue(s.contains("hermesOrigin=O"))
        // Secrets must NOT appear in toString
        assertTrue("toString no debe contener APIKEY-1: $s", s.contains("APIKEY-1").not())
        assertTrue("toString no debe contener SECRET-1: $s", s.contains("SECRET-1").not())
    }
}