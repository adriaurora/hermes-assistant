package dk.foss.jarvis.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationFirstTurnTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private fun repo() = ConversationRepository(ConversationStore(temporaryFolder.newFolder()))

    @Test fun `fresh queue adds one user message`() {
        val r = repo(); assertEquals("hello", r.queueFirstTurn("hello")); assertEquals(1, r.messages.size); assertEquals("user", r.messages[0].role)
    }
    @Test fun `identical unsent queue reuses bubble`() {
        val r = repo(); r.queueFirstTurn("hello"); r.queueFirstTurn("hello"); assertEquals(1, r.messages.count { it.role == "user" })
    }
    @Test fun `different unsent text adds bubble`() {
        val r = repo(); r.queueFirstTurn("one"); r.queueFirstTurn("two"); assertEquals(2, r.messages.count { it.role == "user" })
    }
    @Test fun `session makes identical text a new turn`() {
        val r = repo(); r.queueFirstTurn("hello"); r.setSessionId("s"); r.queueFirstTurn("hello"); assertEquals(2, r.messages.count { it.role == "user" })
    }
    @Test fun `persisted title is first message text`() = runBlocking {
        val dir = temporaryFolder.newFolder(); val store = ConversationStore(dir); val r = ConversationRepository(store)
        r.queueFirstTurn("first message"); r.persist(); assertEquals("first message", store.load(r.activeConversationId)!!.title)
    }
}
