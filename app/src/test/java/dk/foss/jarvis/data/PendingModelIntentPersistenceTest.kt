package dk.foss.jarvis.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Contract tests for the durable, per-conversation model intent queue. */
class PendingModelIntentPersistenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun newRepo(dir: java.io.File) = ConversationRepository(ConversationStore(dir))

    @Test fun `set survives process recreation`() = runBlocking {
        val dir = temporaryFolder.newFolder(); val first = newRepo(dir)
        first.recordPendingModelIntent(PendingModelIntent.Set("m1", "Provider · m1"))
        val restored = newRepo(dir); restored.restoreLatest()
        assertEquals(PendingModelIntent.Set("m1", "Provider · m1"), restored.pendingModelIntent)
    }

    @Test fun `clear survives process recreation`() = runBlocking {
        val dir = temporaryFolder.newFolder(); val first = newRepo(dir)
        first.recordPendingModelIntent(PendingModelIntent.Clear)
        val restored = newRepo(dir); restored.restoreLatest()
        assertEquals(PendingModelIntent.Clear, restored.pendingModelIntent)
    }

    @Test fun `pending intent persists with empty history`() = runBlocking {
        val dir = temporaryFolder.newFolder(); val r = newRepo(dir)
        r.recordPendingModelIntent(PendingModelIntent.Set("m", "M"))
        assertTrue(ConversationStore(dir).list().isEmpty())
        assertNotNull(r.pendingModelIntent)
    }

    @Test fun `set replaces an older pending intent durably`() = runBlocking {
        val dir = temporaryFolder.newFolder(); val r = newRepo(dir)
        r.recordPendingModelIntent(PendingModelIntent.Set("old", "Old"))
        r.recordPendingModelIntent(PendingModelIntent.Set("new", "New"))
        val restored = newRepo(dir); restored.restoreLatest()
        assertEquals(PendingModelIntent.Set("new", "New"), restored.pendingModelIntent)
    }

    @Test fun `clear replaces pending set durably`() = runBlocking {
        val dir = temporaryFolder.newFolder(); val r = newRepo(dir)
        r.recordPendingModelIntent(PendingModelIntent.Set("m", "M")); r.recordPendingModelIntent(PendingModelIntent.Clear)
        val restored = newRepo(dir); restored.restoreLatest()
        assertEquals(PendingModelIntent.Clear, restored.pendingModelIntent)
    }

    @Test fun `ack consumption removes set durably`() = runBlocking {
        val dir = temporaryFolder.newFolder(); val r = newRepo(dir)
        val intent = PendingModelIntent.Set("m", "M"); r.pendingModelIntent = intent; r.persist()
        r.consumePendingModelIntentDurably(intent)
        val restored = newRepo(dir); restored.restoreLatest()
        assertNull(restored.pendingModelIntent)
    }

    @Test fun `ack consumption removes explicit clear durably`() = runBlocking {
        val dir = temporaryFolder.newFolder(); val r = newRepo(dir)
        r.recordPendingModelIntent(PendingModelIntent.Clear); r.consumePendingModelIntentDurably(PendingModelIntent.Clear)
        val restored = newRepo(dir); restored.restoreLatest()
        assertNull(restored.pendingModelIntent)
    }

    @Test fun `failed operation remains queued`() = runBlocking {
        val dir = temporaryFolder.newFolder(); val r = newRepo(dir)
        r.recordPendingModelIntent(PendingModelIntent.Set("m", "M"))
        // Rejection deliberately has no consume call.
        val restored = newRepo(dir); restored.restoreLatest()
        assertNotNull(restored.pendingModelIntent)
    }

    @Test fun `conversations isolate pending intents`() = runBlocking {
        val dir = temporaryFolder.newFolder(); val r = newRepo(dir)
        val first = r.activeConversationId
        r.recordPendingModelIntent(PendingModelIntent.Set("one", "One"))
        r.startNewAtomically(); val second = r.activeConversationId
        assertNotEquals(first, second); assertNull(r.pendingModelIntent)
        r.pendingModelIntent = PendingModelIntent.Clear; r.persist(); r.open(first)
        assertEquals(PendingModelIntent.Set("one", "One"), r.pendingModelIntent)
    }

    @Test fun `new conversation does not inherit pending intent`() = runBlocking {
        val dir = temporaryFolder.newFolder(); val r = newRepo(dir)
        r.recordPendingModelIntent(PendingModelIntent.Clear); r.startNewAtomically()
        assertNull(r.pendingModelIntent)
    }

    @Test fun `ack only clears matching intent`() = runBlocking {
        val dir = temporaryFolder.newFolder(); val r = newRepo(dir)
        val current = PendingModelIntent.Set("current", "Current")
        r.pendingModelIntent = current; r.persist()
        r.consumePendingModelIntent(PendingModelIntent.Set("other", "Other")); r.persist()
        assertEquals(current, r.pendingModelIntent)
    }
}
