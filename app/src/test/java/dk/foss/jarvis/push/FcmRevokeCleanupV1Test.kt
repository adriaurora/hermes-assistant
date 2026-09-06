package dk.foss.jarvis.push

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dk.foss.jarvis.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Extends FcmRevokeCleanupTest coverage to the V1 model path:
 * saveV1, loadOrMigrate, onComplete with/without pending credential clear.
 */
class FcmRevokeCleanupV1Test {

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

    @get:Rule
    val tmp = TemporaryFolder()

    private fun make(): Triple<PushPrefs, SecureStore, DeviceRegistryStore> {
        val ds = PreferenceDataStoreFactory.create { File(tmp.newFolder(), "p.preferences_pb") }
        val s = SecureStore(FakeCipher(), MemBlobs())
        return Triple(PushPrefs(ds), s, DeviceRegistryStore(s))
    }

    @Test fun v1SaveV1LoadsWithDeviceSecret() = runBlocking {
        val (p, s, r) = make()
        r.saveV1("dev-v1", "secret-v1", "push-endpoint", "http://hermes", "api-key")
        val reg = r.load()
        assertNotNull(reg)
        assertEquals("dev-v1", reg!!.deviceId)
        assertEquals("secret-v1", reg.deviceSecret)
        assertEquals("http://hermes", reg.hermesOrigin)
        assertEquals("api-key", reg.apiKey)
    }

    @Test fun v1SaveV1ThenClearReturnsNull() = runBlocking {
        val (p, s, r) = make()
        r.saveV1("dev-v1", "secret-v1", "push-endpoint", "http://hermes", "api-key")
        r.clear()
        assertNull(r.load())
    }

    @Test fun v1OnCompleteWithPendingClearPurgesEverythingAndClearsToken() = runBlocking {
        val (p, s, r) = make()
        r.saveV1("dev-v1", "secret-v1", "push-endpoint", "http://hermes", "api-key")
        s.saveToken("bearer-token")
        p.setPendingRevoke(true)
        p.setPendingCredentialClear(true)

        FcmRevokeCleanup.onComplete(r, s, p) {}

        // All registry fields cleared
        assertNull(r.load())
        assertNull(s.loadDeviceId())
        assertNull(s.loadPushEndpoint())
        assertNull(s.loadPushOrigin())
        assertNull(s.loadPushApiKey())
        assertNull(s.loadDeviceSecret())
        // Bearer token cleared
        assertNull(s.loadToken())
        // Flags cleared
        assertFalse(p.isPendingRevoke())
        assertFalse(p.isPendingCredentialClear())
        // Registration state -> DISABLED
        assertEquals(FcmRegistrationState.DISABLED, p.registrationState.first())
    }

    @Test fun v1OnCompleteWithoutPendingClearPurgesRegistryButKeepsBearer() = runBlocking {
        val (p, s, r) = make()
        r.saveV1("dev-v1", "secret-v1", "push-endpoint", "http://hermes", "api-key")
        s.saveToken("bearer-token")
        p.setPendingRevoke(true)
        p.setPendingCredentialClear(false)

        FcmRevokeCleanup.onComplete(r, s, p) {}

        // Registry cleared
        assertNull(r.load())
        // Bearer preserved
        assertEquals("bearer-token", s.loadToken())
        // Flags
        assertFalse(p.isPendingRevoke())
        assertFalse(p.isPendingCredentialClear())
        // State
        assertEquals(FcmRegistrationState.DISABLED, p.registrationState.first())
    }

    @Test fun v1CompletionReRegistersWhenPushWasReEnabled() = runBlocking {
        val (p, s, r) = make()
        r.saveV1("dev-v1", "secret-v1", "push-endpoint", "http://hermes", "api-key")
        p.enable()
        p.setPendingRevoke(true)

        var called = false
        FcmRevokeCleanup.onComplete(r, s, p) {
            called = true
            p.setRegistrationState(FcmRegistrationState.REGISTERING)
        }

        assertTrue("re-registration callback must be invoked once", called)
        assertEquals(FcmRegistrationState.REGISTERING, p.registrationState.first())
        assertFalse(p.isPendingRevoke())
    }
}