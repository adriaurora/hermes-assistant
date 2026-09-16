package dk.foss.jarvis.data

import dk.foss.jarvis.hermes.ChatTransportKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersistenceRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `ack persists session binding before consuming draft`() = runBlocking {
        val dir = temp.newFolder()
        val repo = ConversationRepository(ConversationStore(dir))
        val intent = PendingModelIntent.Set("chosen", "Chosen")
        repo.recordPendingModelIntent(intent)
        repo.queueFirstTurn("hello")
        repo.bindSession("origin", "server-session", ChatTransportKind.SESSIONS)
        repo.consumePendingModelIntentDurably(intent)
        val restored = ConversationRepository(ConversationStore(dir))
        restored.restoreLatest()
        assertEquals("server-session", restored.sessionId)
        assertEquals("hello", restored.lastUserTurnText())
        assertNull(restored.pendingModelIntent)
    }

    @Test fun `failed draft deletion retains intent in memory`() = runBlocking {
        val dir = temp.newFolder()
        val repo = ConversationRepository(ConversationStore(dir))
        repo.recordPendingModelIntent(PendingModelIntent.Clear)
        val draft = File(dir, "pending-${repo.activeConversationId}.json")
        check(draft.delete()); check(draft.mkdir())
        File(draft, "blocks-deletion").writeText("x")
        assertTrue(runCatching { repo.consumePendingModelIntentDurably(PendingModelIntent.Clear) }.isFailure)
        assertEquals(PendingModelIntent.Clear, repo.pendingModelIntent)
    }

    @Test fun `old conversation ack cannot consume identical new conversation choice`() = runBlocking {
        val dir = temp.newFolder()
        val repo = ConversationRepository(ConversationStore(dir))
        val oldId = repo.activeConversationId
        repo.recordPendingModelIntent(PendingModelIntent.Clear)
        repo.startNewAtomically()
        repo.recordPendingModelIntent(PendingModelIntent.Clear)
        repo.consumePendingModelIntentDurably(PendingModelIntent.Clear, oldId)
        val restored = ConversationRepository(ConversationStore(dir))
        restored.restoreLatest()
        assertEquals(PendingModelIntent.Clear, restored.pendingModelIntent)
    }

    @Test fun `deleting active conversation removes draft and persists new active identity`() = runBlocking {
        val dir = temp.newFolder()
        val repo = ConversationRepository(ConversationStore(dir))
        val oldId = repo.activeConversationId
        repo.recordPendingModelIntent(PendingModelIntent.Clear)
        repo.addMessage("user", "hello"); repo.persist()
        repo.delete(oldId)
        val restored = ConversationRepository(ConversationStore(dir))
        restored.restoreLatest()
        assertEquals(repo.activeConversationId, restored.activeConversationId)
        assertNotEquals(oldId, restored.activeConversationId)
        assertNull(ConversationStore(dir).loadPendingModelIntent(oldId))
        assertTrue(restored.messages.isEmpty())
    }

    @Test fun `delta during write remains dirty until next snapshot is saved`() {
        val revision = PersistenceRevision()
        revision.changed()
        val writing = revision.current
        revision.changed()
        revision.saved(writing)
        assertTrue(revision.isDirty)
        revision.saved(revision.current)
        assertFalse(revision.isDirty)
    }
}
