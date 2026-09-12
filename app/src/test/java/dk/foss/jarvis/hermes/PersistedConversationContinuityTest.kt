package dk.foss.jarvis.hermes

import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.ConversationStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression coverage for pre-RC conversation files after legacy transport removal. */
class PersistedConversationContinuityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun `old persisted conversation loads and lists but cannot continue or select legacy`() = runBlocking {
        val id = "old-conversation"
        val store = ConversationStore(temporaryFolder.newFolder())
        // This is the pre-RC shape: a legacy transport marker, no usable Sessions
        // identity, and an already-started local transcript.
        store.dir.resolve("$id.json").writeText(
            """{"id":"$id","title":"Old chat","createdAt":1,"updatedAt":2,"transport":"LEGACY_CHAT","messages":[{"role":"user","text":"hello"}]}""",
        )

        val repo = ConversationRepository(store)
        assertEquals(id, store.load(id)!!.id)
        assertEquals(id, repo.list().single().id)
        repo.open(id)
        assertEquals("hello", repo.messages.single().text)
        assertEquals(null, repo.sessionId)
        assertEquals(null, repo.transport)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"features":{"session_chat":true}}"""))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val plan = resolveContinuation(repo, HermesClient(baseUrl, "old-key"), baseUrl, "old-key")
        assertTrue(plan is ContinuationPlan.Send)
        assertTrue((plan as ContinuationPlan.Send).decision is ChatTransportDecision.Blocked)
        assertEquals("/v1/capabilities", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }
}
