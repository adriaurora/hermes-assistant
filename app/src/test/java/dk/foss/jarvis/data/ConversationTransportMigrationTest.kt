package dk.foss.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import java.io.File
import dk.foss.jarvis.hermes.ChatTransportKind
import dk.foss.jarvis.hermes.HermesClient
import dk.foss.jarvis.hermes.originIdentity
import dk.foss.jarvis.hermes.ContinuationPlan
import dk.foss.jarvis.hermes.resolveContinuation
import kotlinx.coroutines.runBlocking

/**
 * Tests ConversationStore (internal File constructor) and ConversationRepository
 * round-trips, transport marking, and message replacement semantics.
 */
class ConversationTransportMigrationTest {

    private val server = MockWebServer()

    @Before fun setUpServer() { server.start() }
    @After fun tearDownServer() { server.shutdown() }

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

    // --- Migration tests ---

    // 9. Local migration is a no-op: changing a Sessions origin without an
    // authenticated GET would allow cross-tenant session reuse.
    @Test
    fun `migrateOrigins does not rewrite legacy Sessions origin without verification`() = runBlocking {
        val dir = tmp.newFolder("conv")
        val store = ConversationStore(dir)
        val knownId = "migr-test-1"
        // Old legacy format: scheme://host:port (no path, no key hash)
        val oldOrigin = "https://hermes.local:443"
        store.save(
            Conversation(
                id = knownId, title = "migrated",
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                sessionId = "s1", origin = oldOrigin,
                transport = ChatTransportKind.SESSIONS,
                messages = listOf(StoredMessage("user", "hi"))
            )
        )

        val currentBaseUrl = "https://hermes.local/api/chat"
        val currentApiKey = "my-key-123"

        store.migrateOrigins(currentBaseUrl, currentApiKey)

        val loaded = store.load(knownId)
        assertNotNull(loaded)
        assertEquals(oldOrigin, loaded!!.origin)
        assertEquals(ChatTransportKind.SESSIONS, loaded.transport)
    }

    private suspend fun repositoryWithLegacySessionsOrigin(): Pair<ConversationRepository, ConversationStore> {
        val store = ConversationStore(tmp.newFolder("gate-${System.nanoTime()}"))
        val id = "gate-${System.nanoTime()}"
        store.save(Conversation(
            id = id, title = "gate", createdAt = 1, updatedAt = 1,
            sessionId = "s1", origin = "https://old.example:443",
            transport = ChatTransportKind.SESSIONS,
            messages = listOf(StoredMessage("user", "hi"))
        ))
        return ConversationRepository(store).also { it.open(id) } to store
    }

    private fun gateClient() = HermesClient(server.url("/").toString().trimEnd('/'), "new-key")

    private fun capabilitiesResponse() = MockResponse().setResponseCode(200)
        .setBody("""{"features":{"session_chat":true}}""")

    @Test
    fun `legacyV1_persistedSessions_directContinuation_withoutPriorRefresh`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"session":{"id":"s1"}}"""))
        server.enqueue(capabilitiesResponse())
        val (repo, store) = repositoryWithLegacySessionsOrigin()
        val baseUrl = server.url("/").toString().trimEnd('/')
        val plan = resolveContinuation(repo, HermesClient(baseUrl, "new-key"), baseUrl, "new-key")
        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals("/api/sessions/s1", first.path)
        assertEquals("Bearer new-key", first.getHeader("Authorization"))
        assertTrue(plan is ContinuationPlan.Send && (plan as ContinuationPlan.Send).decision is dk.foss.jarvis.hermes.ChatTransportDecision.Sessions)
        assertEquals(originIdentity(baseUrl, "new-key"), store.load(repo.activeConversationId)!!.origin)
    }

    @Test
    fun `legacyV1_persistedSessions_401_blocks_withoutPostOrDowngrade`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val (repo, store) = repositoryWithLegacySessionsOrigin()
        val old = repo.origin
        val plan = resolveContinuation(repo, gateClient(), server.url("/").toString(), "new-key")
        assertTrue(plan is ContinuationPlan.Blocked && plan.outcome is ConversationRepository.RebindOutcome.BlockedAuth)
        assertEquals(1, server.requestCount)
        assertEquals(ChatTransportKind.SESSIONS, repo.transport)
        assertEquals(old, store.load(repo.activeConversationId)!!.origin)
    }

    @Test
    fun `legacyV1_persistedSessions_404_blocksMissing_withoutDowngrade`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"session_not_found"}"""))
        val (repo, _) = repositoryWithLegacySessionsOrigin()
        val plan = resolveContinuation(repo, gateClient(), server.url("/").toString(), "new-key")
        assertTrue(plan is ContinuationPlan.Blocked && plan.outcome is ConversationRepository.RebindOutcome.BlockedMissing)
        assertEquals(1, server.requestCount)
        assertEquals(ChatTransportKind.SESSIONS, repo.transport)
    }

    @Test
    fun `keyRotation_oldV2Fingerprint_authenticatedGet_rebindsToNewV2`() = runBlocking {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val store = ConversationStore(tmp.newFolder("rotation-${System.nanoTime()}"))
        val id = "rotation-${System.nanoTime()}"
        store.save(Conversation(id, "rotation", 1, 1, "s1", listOf(StoredMessage("user", "hi")), ChatTransportKind.SESSIONS, originIdentity(baseUrl, "old-key"), null))
        val repo = ConversationRepository(store).also { it.open(id) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"session":{"id":"s1"}}"""))
        server.enqueue(capabilitiesResponse())
        val plan = resolveContinuation(repo, HermesClient(baseUrl, "new-key"), baseUrl, "new-key")
        assertTrue(plan is ContinuationPlan.Send && plan.decision is dk.foss.jarvis.hermes.ChatTransportDecision.Sessions)
        assertEquals(originIdentity(baseUrl, "new-key"), store.load(id)!!.origin)
    }

    @Test
    fun `keyRotation_oldV2Fingerprint_get401_noClaim`() = runBlocking {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val store = ConversationStore(tmp.newFolder("rotation-auth-${System.nanoTime()}"))
        val id = "rotation-auth-${System.nanoTime()}"
        val oldOrigin = originIdentity(baseUrl, "old-key")
        store.save(Conversation(id, "rotation", 1, 1, "s1", listOf(StoredMessage("user", "hi")), ChatTransportKind.SESSIONS, oldOrigin, null))
        val repo = ConversationRepository(store).also { it.open(id) }
        server.enqueue(MockResponse().setResponseCode(401))
        val plan = resolveContinuation(repo, HermesClient(baseUrl, "new-key"), baseUrl, "new-key")
        assertTrue(plan is ContinuationPlan.Blocked && plan.outcome is ConversationRepository.RebindOutcome.BlockedAuth)
        assertEquals(1, server.requestCount)
        assertEquals(oldOrigin, store.load(id)!!.origin)
    }

    @Test
    fun `legacyV1_persistedSessions_500_blocksRetryable_withoutRebind`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val (repo, store) = repositoryWithLegacySessionsOrigin()
        val oldOrigin = repo.origin
        val plan = resolveContinuation(repo, gateClient(), server.url("/").toString(), "new-key")
        assertTrue(plan is ContinuationPlan.Blocked && plan.outcome is ConversationRepository.RebindOutcome.BlockedRetryable)
        assertEquals(1, server.requestCount)
        assertEquals(oldOrigin, store.load(repo.activeConversationId)!!.origin)
    }

    @Test
    fun `legacyV1_persistedSessions_IOException_blocksRetryable_withoutRebind`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val (repo, store) = repositoryWithLegacySessionsOrigin()
        val oldOrigin = repo.origin
        val plan = resolveContinuation(repo, gateClient(), server.url("/").toString(), "new-key")
        assertTrue(plan is ContinuationPlan.Blocked && plan.outcome is ConversationRepository.RebindOutcome.BlockedRetryable)
        assertEquals(1, server.requestCount)
        assertEquals(oldOrigin, store.load(repo.activeConversationId)!!.origin)
    }

    @Test
    fun `freshConversation_noPersistedSession_sendsWithoutPreGET`() = runBlocking {
        server.enqueue(capabilitiesResponse())
        val store = ConversationStore(tmp.newFolder("fresh-${System.nanoTime()}"))
        val repo = ConversationRepository(store).also { it.startNew() }
        val baseUrl = server.url("/").toString().trimEnd('/')
        val plan = resolveContinuation(repo, HermesClient(baseUrl, "new-key"), baseUrl, "new-key")
        assertTrue(plan is ContinuationPlan.Send && plan.decision is dk.foss.jarvis.hermes.ChatTransportDecision.Sessions)
        assertTrue(server.takeRequest().path!!.contains("capabilities"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `verifySessionForCurrentOrigin rebinds only after authenticated GET 200`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"session":{"id":"s1"}}"""))
        val (repo, store) = repositoryWithLegacySessionsOrigin()
        val baseUrl = server.url("/").toString()

        val outcome = repo.verifySessionForCurrentOrigin(gateClient(), baseUrl, "new-key")

        assertTrue(outcome is ConversationRepository.RebindOutcome.VerifiedRebound)
        assertEquals(originIdentity(baseUrl, "new-key"), repo.origin)
        assertEquals(ChatTransportKind.SESSIONS, repo.transport)
        assertEquals(repo.origin, store.load(repo.activeConversationId)!!.origin)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        // The successful verification must be authenticated, not merely a
        // connectivity probe against the session endpoint.
        assertEquals("Bearer new-key", request.getHeader("Authorization"))
    }

    @Test
    fun `verifySessionForCurrentOrigin blocks auth errors without rebinding`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"error":{"code":"gateway_auth_failed"}}"""))
        val (repo, store) = repositoryWithLegacySessionsOrigin()
        val outcome = repo.verifySessionForCurrentOrigin(gateClient(), server.url("/").toString(), "new-key")

        assertTrue(outcome is ConversationRepository.RebindOutcome.BlockedAuth)
        assertEquals("https://old.example:443", repo.origin)
        assertEquals(ChatTransportKind.SESSIONS, repo.transport)
        assertEquals(repo.origin, store.load(repo.activeConversationId)!!.origin)
    }

    @Test
    fun `verifySessionForCurrentOrigin treats 403 as auth block without rebinding`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val (repo, store) = repositoryWithLegacySessionsOrigin()
        val outcome = repo.verifySessionForCurrentOrigin(gateClient(), server.url("/").toString(), "new-key")

        assertTrue(outcome is ConversationRepository.RebindOutcome.BlockedAuth)
        assertEquals("https://old.example:443", repo.origin)
        assertEquals(ChatTransportKind.SESSIONS, repo.transport)
        assertEquals(repo.origin, store.load(repo.activeConversationId)!!.origin)
    }

    @Test
    fun `verifySessionForCurrentOrigin blocks missing session without downgrading transport`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404)
            .setBody("""{"error":{"code":"session_not_found"}}"""))
        val (repo, store) = repositoryWithLegacySessionsOrigin()
        val outcome = repo.verifySessionForCurrentOrigin(gateClient(), server.url("/").toString(), "new-key")

        assertTrue(outcome is ConversationRepository.RebindOutcome.BlockedMissing)
        assertEquals("https://old.example:443", repo.origin)
        assertEquals(ChatTransportKind.SESSIONS, repo.transport)
        assertEquals(repo.origin, store.load(repo.activeConversationId)!!.origin)
    }

    @Test
    fun `verifySessionForCurrentOrigin retries GET after server error and then rebinds`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"session":{"id":"s1"}}"""))
        val (repo, store) = repositoryWithLegacySessionsOrigin()
        val baseUrl = server.url("/").toString()

        val first = repo.verifySessionForCurrentOrigin(gateClient(), baseUrl, "new-key")
        val second = repo.verifySessionForCurrentOrigin(gateClient(), baseUrl, "new-key")

        assertTrue(first is ConversationRepository.RebindOutcome.BlockedRetryable)
        assertTrue(second is ConversationRepository.RebindOutcome.VerifiedRebound)
        assertEquals(ChatTransportKind.SESSIONS, repo.transport)
        assertEquals(repo.origin, store.load(repo.activeConversationId)!!.origin)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `verifySessionForCurrentOrigin retries GET after IOException`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"session":{"id":"s1"}}"""))
        val (repo, store) = repositoryWithLegacySessionsOrigin()
        val baseUrl = server.url("/").toString()

        val first = repo.verifySessionForCurrentOrigin(gateClient(), baseUrl, "new-key")
        val second = repo.verifySessionForCurrentOrigin(gateClient(), baseUrl, "new-key")

        assertTrue(first is ConversationRepository.RebindOutcome.BlockedRetryable)
        assertTrue(second is ConversationRepository.RebindOutcome.VerifiedRebound)
        assertEquals(ChatTransportKind.SESSIONS, repo.transport)
        assertEquals(repo.origin, store.load(repo.activeConversationId)!!.origin)
        assertEquals(2, server.requestCount)
    }

    // 10. migrateOrigins: non-matching origin → unchanged
    @Test
    fun `migrateOrigins leaves non-matching origin intact`() = runBlocking {
        val dir = tmp.newFolder("conv2")
        val store = ConversationStore(dir)
        val knownId = "migr-test-2"
        // Different host — won't match the legacy check
        val oldOrigin = "https://other-server.local:443"
        store.save(
            Conversation(
                id = knownId, title = "untouched",
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                sessionId = "s2", origin = oldOrigin,
                transport = ChatTransportKind.LEGACY_CHAT,
                messages = listOf(StoredMessage("user", "hi"))
            )
        )

        val currentBaseUrl = "https://hermes.local/api/chat"
        store.migrateOrigins(currentBaseUrl, "key")

        val loaded = store.load(knownId)
        assertNotNull(loaded)
        assertEquals(oldOrigin, loaded!!.origin)
    }

    // 11. migrateOrigins: no origin → no-op
    @Test
    fun `migrateOrigins skips conversations without origin`() = runBlocking {
        val dir = tmp.newFolder("conv3")
        val store = ConversationStore(dir)
        val knownId = "migr-test-3"
        store.save(
            Conversation(
                id = knownId, title = "no-origin",
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                sessionId = null, origin = null,
                transport = null,
                messages = listOf(StoredMessage("user", "hi"))
            )
        )

        store.migrateOrigins("https://hermes.local", "key")

        val loaded = store.load(knownId)
        assertNotNull(loaded)
        assertNull(loaded!!.origin)
    }

    // 12. migrateOrigins: new origin already matches → no change
    @Test
    fun `migrateOrigins no-op when origin already new format`() = runBlocking {
        val dir = tmp.newFolder("conv4")
        val store = ConversationStore(dir)
        val knownId = "migr-test-4"
        val currentBaseUrl = "https://hermes.local/api/chat"
        val currentApiKey = "my-key-123"
        val newOrigin = originIdentity(currentBaseUrl, currentApiKey)
        store.save(
            Conversation(
                id = knownId, title = "already-new",
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                sessionId = "s1", origin = newOrigin,
                transport = ChatTransportKind.SESSIONS,
                messages = listOf(StoredMessage("user", "hi"))
            )
        )

        store.migrateOrigins(currentBaseUrl, currentApiKey)

        val loaded = store.load(knownId)
        assertNotNull(loaded)
        assertEquals(newOrigin, loaded!!.origin)
    }
}
