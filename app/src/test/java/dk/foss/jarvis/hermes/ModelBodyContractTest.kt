package dk.foss.jarvis.hermes

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests setSessionModel and clearSessionModel body contracts, and response decoding.
 */
class ModelBodyContractTest {

    private val server = MockWebServer()

    @Before fun setUp() { server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun client() = HermesClient(server.url("/").toString().trimEnd('/'), "test-key")

    // 1. setSessionModel("s1","m1") → body EXACTLY {"model":"m1","require_model_lock":true}
    @Test
    fun `setSessionModel body has no provider key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"object":"hermes.session.model_lock","session_id":"s1","automatic":false,"runtime":{"model":"m1","route_source":"session_model_lock"}}"""))
        client().setSessionModel("s1", "m1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        val body = req.body.readUtf8()
        assertEquals("""{"model":"m1"}""", body)
        assertFalse(body.contains("provider"))
    }

    // 2. setSessionModel with provider → body has provider
    @Test
    fun `setSessionModel with provider includes provider`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"object":"hermes.session.model_lock","session_id":"s1","automatic":false}"""))
        client().setSessionModel("s1", "m1", "custom")
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(body.contains("provider"))
        assertTrue(body.contains("custom"))
        assertEquals("""{"model":"m1","provider":"custom"}""", body)
    }

    // 3. clearSessionModel("s1") → body EXACTLY {"model":null}
    @Test
    fun `clearSessionModel body exactly model null`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"object":"hermes.session.model_lock","session_id":"s1","automatic":true}"""))
        client().clearSessionModel("s1")
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertEquals("""{"model":null}""", body)
    }

    // 4. setSessionModel 400 → failure
    @Test
    fun `setSessionModel 400 returns failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        val result = client().setSessionModel("s1", "m1")
        assertTrue(result.isFailure)
    }

    // 5. ModelLockResponse with runtime decodes model
    @Test
    fun `ModelLockResponse runtime model decodes`() {
        val json = """{"object":"hermes.session.model_lock","session_id":"s1","automatic":false,"runtime":{"model":"m1","route_source":"session_model_lock"}}"""
        val resp = HermesJson.decodeFromString(ModelLockResponse.serializer(), json)
        assertEquals("m1", resp.runtime?.model)
        assertEquals("session_model_lock", resp.runtime?.route_source)
    }

    // 6. SessionEnvelope with runtime decodes
    @Test
    fun `SessionEnvelope with runtime decodes`() {
        val json = """{"session":{"id":"s1"},"runtime":{"model":"m2","route_source":"global"}}"""
        val env = HermesJson.decodeFromString(SessionEnvelope.serializer(), json)
        assertEquals("s1", env.session.id)
        assertEquals("m2", env.runtime?.model)
        assertEquals("global", env.runtime?.route_source)
    }
}