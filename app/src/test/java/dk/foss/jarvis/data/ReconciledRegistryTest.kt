package dk.foss.jarvis.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for DeviceRegistryStore.loadOrMigrate across the reconciled model:
 * saveV1, legacy (no secret), partial records, and clear().
 */
class ReconciledRegistryTest {

    private class FakeCipher : AeadCipher {
        override fun encrypt(plainText: String): String = "enc($plainText)"
        override fun decrypt(blob: String): String? = blob.removePrefix("enc(").removeSuffix(")")
    }
    private class MemBlobs : SecretBlobStore {
        val map = mutableMapOf<String, String>()
        override fun get(a: String) = map[a]
        override fun put(a: String, b: String) { map[a] = b }
        override fun remove(a: String) { map.remove(a) }
    }

    private fun newStore(blobs: MemBlobs = MemBlobs()) =
        DeviceRegistryStore(SecureStore(FakeCipher(), blobs)) to blobs

    @Test fun saveV1LoadOrMigrateReturnsRegisteredWithDeviceSecret() {
        val (store, _) = newStore()
        store.saveV1("dev-1", "secret-1", "push-endpoint", "http://hermes", "api-key")
        val settings = JarvisSettings("http://hermes", "api-key")
        val result = store.loadOrMigrate(settings)
        assertTrue("loadOrMigrate must return Registered", result is RegistryState.Registered)
        val reg = (result as RegistryState.Registered).registration
        assertEquals("dev-1", reg.deviceId)
        assertEquals("secret-1", reg.deviceSecret)
        assertEquals("http://hermes", reg.hermesOrigin)
        assertEquals("api-key", reg.apiKey)
    }

    @Test fun legacyRegistrationNoSecretSettingsReturnsRegisteredWithNullSecret() {
        val (store, _) = newStore()
        // save() with origin/apiKey -> Registered, but deviceSecret stays null
        store.save("dev-legacy", "push-endpoint", "http://hermes", "api-key")
        val settings = JarvisSettings("http://hermes", "api-key")
        val result = store.loadOrMigrate(settings)
        assertTrue("loadOrMigrate must return Registered", result is RegistryState.Registered)
        val reg = (result as RegistryState.Registered).registration
        assertEquals("dev-legacy", reg.deviceId)
        assertNull("deviceSecret must be null for legacy save()", reg.deviceSecret)
        assertEquals("http://hermes", reg.hermesOrigin)
        assertEquals("api-key", reg.apiKey)
    }

    @Test fun updateCredentialsRefreshesApiKeyNotOriginOrSecret() {
        val (store, _) = newStore()
        store.saveV1("dev-1", "secret-1", "push-endpoint", "http://hermes", "old-key")
        store.updateCredentials("new-key")
        val reg = store.load()
        assertNotNull(reg)
        assertEquals("new-key", reg!!.apiKey)
        assertEquals("http://hermes", reg.hermesOrigin)
        assertEquals("secret-1", reg.deviceSecret)
    }

    @Test fun clearLeavesLoadNullAndLoadOrMigrateEmpty() {
        val (store, _) = newStore()
        store.saveV1("dev-1", "secret-1", "push-endpoint", "http://hermes", "api-key")
        store.clear()
        assertNull(store.load())
        assertEquals(RegistryState.Empty, store.loadOrMigrate(JarvisSettings("http://hermes", "api-key")))
    }

    @Test fun partialRecordWithoutOriginApikeyReturnsLegacyPendingWithSameDeviceId() {
        val blobs = MemBlobs()
        // Write only deviceId and pushEndpoint (no origin, no apiKey).
        // DeviceRegistryStore.save() also writes origin/apiKey, so we bypass it
        // and write directly to SecureStore via the constructor.
        val secure = SecureStore(FakeCipher(), blobs)
        secure.saveDeviceId("dev-partial")
        secure.savePushEndpoint("push-endpoint")
        val store = DeviceRegistryStore(secure)

        // Settings not configured -> LegacyPending
        val result = store.loadOrMigrate(JarvisSettings("", ""))
        assertTrue("loadOrMigrate must return LegacyPending", result is RegistryState.LegacyPending)
        val lp = result as RegistryState.LegacyPending
        assertEquals("dev-partial", lp.deviceId)
        assertEquals("push-endpoint", lp.pushEndpoint)
    }
}