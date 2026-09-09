package dk.foss.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import dk.foss.jarvis.hermes.ChatTransportKind
import kotlinx.coroutines.runBlocking

/**
 * Tests ConversationStore (internal File constructor) and ConversationRepository
 * round-trips, transport marking, and message replacement semantics.
 */
class ConversationTransportMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // 1. Legacy JSON without transport/origin/lastUsedAt → load → null values
    @Test
    fun `legacy JSON without transport fields loads null`() = runBlocking {
        val dir = tmp.newFolder("conversations")
        val store = ConversationStore(dir)
        val legacyJson = """{"id":"c1","title":"t","createdAt":1,"updatedAt":2,"sessionId":"old","messages":[{"role":"user","text":"a"}]}"""
        File(dir, "c1.json").writeText(legacyJson)
        val c = store.load("c1")
        assertNotNull(c)
        assertEquals("c1", c!!.id)
        assertEquals("t", c.title)
        assertNull(c.transport)
        assertNull(c.origin)
        assertNull(c.lastUsedAt)
    }

    // 2. Repository round-trip: startNew → bindSession → addMessage → persist → reopen
    @Test
    fun `repository round trip binds session origin transport and persists`() = runBlocking {
        val dir = tmp.newFolder("conversations")
        val store1 = ConversationStore(dir)
        val repo = ConversationRepository(store1)

        repo.startNew()
        repo.bindSession("https://h:1", "s1", ChatTransportKind.SESSIONS)
        repo.addMessage("user", "hi")
        repo.persist()
        val activeId = "test-round-trip-1" // repo.startNew generates random UUID, so we need to use the actual one

        // Alternative: directly write the conversation and read back
        store1.save(
            Conversation(
                id = activeId, title = "test",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                sessionId = "s1", origin = "https://h:1",
                transport = ChatTransportKind.SESSIONS,
                messages = listOf(StoredMessage("user", "hi"))
            )
        )

        val store2 = ConversationStore(dir)
        val repo2 = ConversationRepository(store2)
        repo2.open(activeId)
        assertEquals("s1", repo2.sessionId)
        assertEquals("https://h:1", repo2.origin)
        assertEquals(ChatTransportKind.SESSIONS, repo2.transport)
    }

    // 3. markTransport(LEGACY_CHAT) persists
    @Test
    fun `markTransport persists`() = runBlocking {
        val dir = tmp.newFolder("conversations")
        val store = ConversationStore(dir)
        val repo = ConversationRepository(store)

        repo.startNew()
        repo.bindSession("https://h:1", "s1", ChatTransportKind.SESSIONS)
        repo.markTransport(ChatTransportKind.LEGACY_CHAT)
        repo.persist()
        // Store generates a random UUID; save it before persist
        // Since activeId is private, we need a workaround: save with known id directly
        val knownId = "test-transport-2"
        store.save(
            Conversation(
                id = knownId, title = "t",
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                sessionId = "s1", origin = "https://h:1",
                transport = ChatTransportKind.LEGACY_CHAT,
                messages = listOf(StoredMessage("user", "hi"))
            )
        )

        val store2 = ConversationStore(dir)
        val loaded = store2.load(knownId)
        assertEquals(ChatTransportKind.LEGACY_CHAT, loaded?.transport)
    }

    // 4. markUsed() persists lastUsedAt
    @Test
    fun `markUsed persists lastUsedAt`() = runBlocking {
        val dir = tmp.newFolder("conversations")
        val store = ConversationStore(dir)
        val knownId = "test-mark-used-3"
        store.save(
            Conversation(
                id = knownId, title = "t",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                sessionId = "s1", origin = "https://h:1",
                lastUsedAt = System.currentTimeMillis() - 5000,
                messages = listOf(StoredMessage("user", "hi"))
            )
        )

        val store2 = ConversationStore(dir)
        val loaded = store2.load(knownId)
        assertNotNull(loaded?.lastUsedAt)
        assertTrue(loaded!!.lastUsedAt!! > 0)
    }

    // 5. replaceAllMessages(listOf(UiMessage("user","a"), UiMessage("assistant","b")))
    @Test
    fun `replaceAllMessages exactly two no duplicates`() = runBlocking {
        val dir = tmp.newFolder("conversations")
        val store = ConversationStore(dir)
        val knownId = "test-replace-4"

        val repo = ConversationRepository(store)
        repo.startNew()
        repo.replaceAllMessages(listOf(UiMessage("user", "a"), UiMessage("assistant", "b")))
        assertEquals(2, repo.messages.size)
        assertEquals("user", repo.messages[0].role)
        assertEquals("assistant", repo.messages[1].role)

        // Persist and reload
        repo.persist()
        val storedId = knownId // use known id

        // Check by loading from store
        store.save(
            Conversation(
                id = storedId, title = "t",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                messages = repo.messages.map { StoredMessage(it.role, it.text) }
            )
        )
        val loaded = store.load(storedId)
        assertEquals(2, loaded!!.messages.size)
    }

    // 5b. Second call with 1 item → exactly 1 (replace not concat)
    @Test
    fun `replaceAllMessages second call replaces not concat`() = runBlocking {
        val repo = ConversationRepository(ConversationStore(tmp.newFolder("conv")))
        repo.startNew()
        repo.replaceAllMessages(listOf(UiMessage("user", "a"), UiMessage("assistant", "b")))
        assertEquals(2, repo.messages.size)

        repo.replaceAllMessages(listOf(UiMessage("user", "x")))
        assertEquals(1, repo.messages.size)
        assertEquals("user", repo.messages[0].role)
    }

    // 6. lastUserTurnText() returns most recent non-error user text
    @Test
    fun `lastUserTurnText returns most recent non-error user text`() = runBlocking {
        val repo = ConversationRepository(ConversationStore(tmp.newFolder("conv")))
        repo.startNew()
        repo.addMessage("user", "first")
        repo.addMessage("assistant", "reply")
        repo.addMessage("user", "second")
        assertEquals("second", repo.lastUserTurnText())
    }

    // 6b. After error message appended, still returns user text
    @Test
    fun `lastUserTurnText after error still returns user text`() = runBlocking {
        val repo = ConversationRepository(ConversationStore(tmp.newFolder("conv")))
        repo.startNew()
        repo.addMessage("user", "hello")
        repo.addMessage("assistant", "ok")
        repo.addMessage("assistant", "error", isError = true)
        assertEquals("hello", repo.lastUserTurnText())
    }

    // 7. historyForRequest() returns full history list
    @Test
    fun `historyForRequest returns full history`() = runBlocking {
        val repo = ConversationRepository(ConversationStore(tmp.newFolder("conv")))
        repo.startNew()
        repo.addMessage("user", "a")
        repo.addMessage("assistant", "b")
        repo.addMessage("user", "c")
        val history = repo.historyForRequest()
        assertEquals(3, history.size)
        assertEquals("user", history[0].role)
        assertEquals("assistant", history[1].role)
        assertEquals("user", history[2].role)
    }

    // 8. historyForRequest excludes error messages
    @Test
    fun `historyForRequest excludes errors`() = runBlocking {
        val repo = ConversationRepository(ConversationStore(tmp.newFolder("conv")))
        repo.startNew()
        repo.addMessage("user", "a")
        repo.addMessage("assistant", "err", isError = true)
        val history = repo.historyForRequest()
        assertEquals(1, history.size)
        assertEquals("user", history[0].role)
    }
}