package dk.foss.jarvis.net

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [AndroidApprovedOriginsStore] backed by a temp-file DataStore.
 *
 * Verifies the persistence contract used by the production network gate.
 */
class AndroidApprovedOriginsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: AndroidApprovedOriginsStore
    private lateinit var ds: DataStore<Preferences>

    private fun setup() {
        ds = PreferenceDataStoreFactory.create { File(tmp.newFolder(), "test.preferences_pb") }
        store = AndroidApprovedOriginsStore(ds)
    }

    @Test fun `initially_empty`() = runBlocking {
        setup()
        val list = store.list()
        assertEquals(0, list.size)
    }

    @Test fun `add_and_isApproved`() = runBlocking {
        setup()
        store.add("http://dev.local:8642")
        assertEquals(
            setOf("http://dev.local:8642"),
            store.list()
        )
        assertEquals(true, store.isApproved("http://dev.local:8642"))
    }

    @Test fun `isApproved_returns_false_for_unapproved`() = runBlocking {
        setup()
        store.add("http://dev.local:8642")
        assertEquals(false, store.isApproved("http://other.local:9000"))
    }

    @Test fun `remove_and_verify_not_approved`() = runBlocking {
        setup()
        store.add("http://dev.local:8642")
        store.remove("http://dev.local:8642")
        assertEquals(0, store.list().size)
        assertEquals(false, store.isApproved("http://dev.local:8642"))
    }

    @Test fun `multiple_approvals_stored_separately`() = runBlocking {
        setup()
        store.add("http://a.local:8642")
        store.add("http://b.local:8642")
        store.add("http://c.local:8642")

        val list = store.list()
        assertEquals(3, list.size)
        assertEquals(true, store.isApproved("http://a.local:8642"))
        assertEquals(true, store.isApproved("http://b.local:8642"))
        assertEquals(true, store.isApproved("http://c.local:8642"))
    }

    @Test fun `remove_nonexistent_is_idempotent`() = runBlocking {
        setup()
        store.remove("http://nonexistent.local")
        assertEquals(0, store.list().size)
    }

    @Test fun `add_duplicate_is_idempotent`() = runBlocking {
        setup()
        store.add("http://dev.local:8642")
        store.add("http://dev.local:8642")
        store.add("http://dev.local:8642")

        assertEquals(1, store.list().size)
        assertEquals(true, store.isApproved("http://dev.local:8642"))
    }

    @Test fun `clearAllExcept_keeps_specific_origin`() = runBlocking {
        setup()
        store.add("http://a.local:8642")
        store.add("http://b.local:8642")
        store.add("http://c.local:8642")

        store.clearAllExcept("http://b.local:8642")

        val approvals = store.list()
        assertEquals(setOf("http://b.local:8642"), approvals)
    }

    @Test fun `clearAllExcept_noop_when_single_origin`() = runBlocking {
        setup()
        store.add("http://dev.local:8642")

        store.clearAllExcept("http://dev.local:8642")

        val approvals = store.list()
        assertEquals(setOf("http://dev.local:8642"), approvals)
    }

    @Test fun `clearAllExcept_nonexistent_keep_is_noop`() = runBlocking {
        setup()
        store.add("http://a.local:8642")
        store.add("http://b.local:8642")

        store.clearAllExcept("http://nonexistent.local")

        val approvals = store.list()
        assertEquals(2, approvals.size)
        assertEquals(true, approvals.contains("http://a.local:8642"))
        assertEquals(true, approvals.contains("http://b.local:8642"))
    }
}