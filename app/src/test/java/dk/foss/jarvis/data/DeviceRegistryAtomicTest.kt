package dk.foss.jarvis.data

import org.junit.Assert.*
import org.junit.Test

class DeviceRegistryAtomicTest {
    private class Cipher : AeadCipher {
        override fun encrypt(plainText: String) = "<$plainText>"
        override fun decrypt(blob: String) = blob.removePrefix("<").removeSuffix(">").takeIf { blob.startsWith("<") }
    }
    private open class Blobs : SecretBlobStore {
        val values = mutableMapOf<String, String>(); var syncWrites = 0; var failNextSync = false
        override fun get(alias: String) = values[alias]
        override fun put(alias: String, blob: String) { values[alias] = blob }
        override fun putSync(alias: String, blob: String) { syncWrites++; if (failNextSync) { failNextSync = false; throw IllegalStateException("simulated kill") }; values[alias] = blob }
        override fun remove(alias: String) { values.remove(alias) }
    }

    @Test fun `reenrollment is one confirmed authoritative write`() {
        val blobs = Blobs(); val store = DeviceRegistryStore(SecureStore(Cipher(), blobs))
        store.saveV1("a", "sa", "ta", "oa", "ka")
        val before = store.load()
        store.saveV1("b", "sb", "tb", "ob", "kb")
        assertEquals(2, blobs.syncWrites)
        assertEquals(DeviceRegistration("b", "tb", "ob", "kb", "sb"), store.load())
        assertNotEquals(before, store.load())
    }

    @Test fun `corrupt atomic blob fails closed instead of reviving legacy`() {
        val blobs = Blobs(); val secure = SecureStore(Cipher(), blobs)
        secure.saveDeviceId("old"); secure.savePushEndpoint("token"); secure.savePushOrigin("origin"); secure.savePushApiKey("key")
        blobs.values[SecureStore.DEVICE_REGISTRATION_ALIAS] = "broken"
        assertNull(DeviceRegistryStore(secure).load())
    }

    @Test fun `clear revokes authoritative and compatibility records`() {
        val blobs = Blobs(); val store = DeviceRegistryStore(SecureStore(Cipher(), blobs))
        store.saveV1("a", "s", "t", "o", "k"); store.clear()
        assertNull(store.load()); assertTrue(blobs.values.containsKey(SecureStore.DEVICE_REGISTRATION_ALIAS))
    }

    @Test fun `v1 credential update changes only the authoritative blob`() {
        val blobs = Blobs(); val store = DeviceRegistryStore(SecureStore(Cipher(), blobs))
        store.saveV1("a", "s", "t", "o", "old-key")
        val legacyBefore = blobs.values.filterKeys { it != SecureStore.DEVICE_REGISTRATION_ALIAS }
        store.updateCredentials("new-key")
        assertEquals(legacyBefore, blobs.values.filterKeys { it != SecureStore.DEVICE_REGISTRATION_ALIAS })
        assertEquals("new-key", store.load()!!.apiKey)
    }

    @Test fun `tombstone prevents legacy resurrection after interrupted revoke`() {
        val blobs = Blobs(); val secure = SecureStore(Cipher(), blobs)
        secure.saveDeviceId("old"); secure.savePushEndpoint("token"); secure.savePushOrigin("origin"); secure.savePushApiKey("key")
        val store = DeviceRegistryStore(secure); store.clear()
        assertNull(store.load()); assertNull(store.loadOrMigrate(JarvisSettings("http://a", "k")).let { (it as? RegistryState.Registered)?.registration })
    }

    @Test fun `failed reenrollment leaves exactly the previous authoritative record`() {
        val blobs = Blobs(); val secure = SecureStore(Cipher(), blobs)
        val store = DeviceRegistryStore(secure); store.saveV1("old", "secret", "token", "origin", "key")
        val old = store.load(); blobs.failNextSync = true
        try { store.saveV1("new", "new-secret", "new-token", "new-origin", "new-key") } catch (_: IllegalStateException) { }
        assertEquals(old, store.load())
    }
}
