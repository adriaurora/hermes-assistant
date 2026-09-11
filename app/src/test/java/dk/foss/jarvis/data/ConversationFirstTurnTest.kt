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
    @Test fun `session retries identical unanswered text without duplicate`() {
        val r = repo(); r.queueFirstTurn("hello"); r.setSessionId("s"); r.queueFirstTurn("hello"); assertEquals(1, r.messages.count { it.role == "user" })
    }
    @Test fun `session retry with different text adds a turn`() {
        val r = repo(); r.queueFirstTurn("hello"); r.setSessionId("s"); r.queueFirstTurn("goodbye"); assertEquals(2, r.messages.count { it.role == "user" })
    }
    @Test fun `session answered identical text is a new turn`() {
        val r = repo(); r.queueFirstTurn("hello"); r.setSessionId("s"); r.addMessage("assistant", "answer"); r.queueFirstTurn("hello"); assertEquals(2, r.messages.count { it.role == "user" })
    }
    @Test fun `session error closed identical text is a new turn`() {
        val r = repo(); r.queueFirstTurn("hello"); r.setSessionId("s"); r.addMessage("assistant", "failed", isError = true); r.queueFirstTurn("hello"); assertEquals(2, r.messages.count { it.role == "user" })
    }
    @Test fun `persisted title is first message text`() = runBlocking {
        val dir = temporaryFolder.newFolder(); val store = ConversationStore(dir); val r = ConversationRepository(store)
        r.queueFirstTurn("first message"); r.persist(); assertEquals("first message", store.load(r.activeConversationId)!!.title)
    }

    @Test fun `switched listener can be unsubscribed`() {
        val r = repo(); var calls = 0
        val unsubscribe = r.onConversationSwitched { calls++ }
        unsubscribe(); r.startNew()
        assertEquals(0, calls)
    }

    @Test fun `unsubscribing one switched listener retains the other`() {
        val r = repo(); var first = 0; var second = 0
        val removeFirst = r.onConversationSwitched { first++ }
        r.onConversationSwitched { second++ }
        removeFirst(); r.startNew()
        assertEquals(0, first); assertEquals(1, second)
    }
}
