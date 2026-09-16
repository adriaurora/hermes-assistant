package dk.foss.jarvis.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the HTTP origin approval integration with [SettingsStore].
 * Uses the same temp-file-backed DataStore pattern as [SettingsStoreMigrationTest].
 */
class SettingsStoreApprovalTest {

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

    private fun newStore(): SettingsStore {
        ds = PreferenceDataStoreFactory.create { File(tmp.newFolder(), "test.preferences_pb") }
        store = SettingsStore(ds, SecureStore(cipher, blobs))
        return store
    }

    @Test fun `approveHttpOrigin_and_retrieve`() = runBlocking {
        newStore()
        val origin = "http://dev.local:8642"

        store.approveHttpOrigin(origin)
        val approvals = store.approvedHttpOrigins.first()
        assertEquals(setOf(origin), approvals)
    }

    @Test fun `revokeHttpOrigin_removes_specific_origin`() = runBlocking {
        newStore()
        store.approveHttpOrigin("http://a.local:8642")
        store.approveHttpOrigin("http://b.local:8642")

        store.revokeHttpOrigin("http://a.local:8642")

        val approvals = store.approvedHttpOrigins.first()
        assertEquals(setOf("http://b.local:8642"), approvals)
    }

    @Test fun `revokeHttpOrigin_idempotent_when_not_present`() = runBlocking {
        newStore()
        store.approveHttpOrigin("http://a.local:8642")

        // Second revoke of the same origin should not error
        store.revokeHttpOrigin("http://a.local:8642")
        store.revokeHttpOrigin("http://a.local:8642")

        val approvals = store.approvedHttpOrigins.first()
        assertTrue(approvals.isEmpty())
    }

    @Test fun `clearAllHttpApprovals_clears_all`() = runBlocking {
        newStore()
        store.approveHttpOrigin("http://a.local:8642")
        store.approveHttpOrigin("http://b.local:8642")
        store.approveHttpOrigin("http://c.local:8642")

        store.clearAllHttpApprovals()

        val approvals = store.approvedHttpOrigins.first()
        assertTrue(approvals.isEmpty())
    }

    @Test fun `empty_set_when_no_approvals`() = runBlocking {
        newStore()
        val approvals = store.approvedHttpOrigins.first()
        assertTrue(approvals.isEmpty())
    }

    @Test fun `approve_is_idempotent`() = runBlocking {
        newStore()
        store.approveHttpOrigin("http://dev.local:8642")
        store.approveHttpOrigin("http://dev.local:8642")

        val approvals = store.approvedHttpOrigins.first()
        assertEquals(1, approvals.size)
    }

    @Test fun `approval_survives_updateConnection`() = runBlocking {
        newStore()
        store.approveHttpOrigin("http://dev.local:8642")

        // Simulate user changing the URL
        store.updateConnection("http://other.local:8642", "tok1")

        // Approval should still be present (independent of baseUrl)
        val approvals = store.approvedHttpOrigins.first()
        assertEquals(setOf("http://dev.local:8642"), approvals)
    }

    @Test fun `clearAllHttpApprovals_with_clear_token`() = runBlocking {
        newStore()
        store.approveHttpOrigin("http://dev.local:8642")
        store.updateConnection("http://dev.local:8642", "tok1")
        // Now clear key: this would normally trigger connectionChanged which could clear approvals

        // In the real flow, FcmConnectionEffects would trigger when apiKey is cleared
        // Here we verify the approval exists before and can be cleared
        var approvals = store.approvedHttpOrigins.first()
        assertEquals(setOf("http://dev.local:8642"), approvals)

        store.clearAllHttpApprovals()
        approvals = store.approvedHttpOrigins.first()
        assertTrue(approvals.isEmpty())
    }
}