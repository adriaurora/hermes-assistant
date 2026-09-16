package dk.foss.jarvis.data

import org.junit.Assert.*
import org.junit.Test

class DeviceRegistryAtomicTest {
    private class Cipher : AeadCipher {
        override fun encrypt(plainText: String) = "<$plainText>"
        override fun decrypt(blob: String) = blob.removePrefix("<").removeSuffix(">").takeIf { blob.startsWith("<") }
    }
    private class Blobs : SecretBlobStore {
        val values = mutableMapOf<String, String>(); var syncWrites = 0
        override fun get(alias: String) = values[alias]
        override fun put(alias: String, blob: String) { values[alias] = blob }
        override fun putSync(alias: String, blob: String) { syncWrites++; values[alias] = blob }
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

    @Test fun `corrupt or incomplete atomic blob falls back to complete legacy record`() {
        val blobs = Blobs(); val secure = SecureStore(Cipher(), blobs)
        secure.saveDeviceId("old"); secure.savePushEndpoint("token"); secure.savePushOrigin("origin"); secure.savePushApiKey("key")
        blobs.values[SecureStore.DEVICE_REGISTRATION_ALIAS] = "broken"
        assertEquals(DeviceRegistration("old", "token", "origin", "key", null), DeviceRegistryStore(secure).load())
    }

    @Test fun `clear revokes authoritative and compatibility records`() {
        val blobs = Blobs(); val store = DeviceRegistryStore(SecureStore(Cipher(), blobs))
        store.saveV1("a", "s", "t", "o", "k"); store.clear()
        assertNull(store.load()); assertTrue(blobs.values.isEmpty())
    }
}
