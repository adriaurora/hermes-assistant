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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Tests setSessionModel and clearSessionModel body contracts, and response decoding.
 *
 * Contract (sessions-api-chat.md §8): POST /api/sessions/{id}/model sends EXACTLY
 * {"model":"<string id>"} — no provider key. Clear sends {"model":null}.
 */
class ModelBodyContractTest {

    private val server = MockWebServer()

    @Before fun setUp() { server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun client() = HermesClient(server.url("/").toString().trimEnd('/'), "test-key")

    // 1. setSessionModel("s1","m1") → body EXACTLY {"model":"m1"}
    @Test
    fun `setSessionModel body is exactly model key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"object":"hermes.session.model_lock","session_id":"s1","automatic":false,"runtime":{"model":"m1","route_source":"session_model_lock"}}"""))
        client().setSessionModel("s1", "m1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        val body = req.body.readUtf8()
        assertEquals("""{"model":"m1"}""", body)
        // Regression: ensure no provider key is ever included
        assertFalse("Body must NOT contain 'provider' key", body.contains("provider"))
        // Verify only the "model" key exists
        val keys = body.trim('{', '}').split(",").map { it.trim().split(":")[0].trim('"') }
        assertEquals(listOf("model"), keys)
    }

    @Test
    fun `setSessionModel escapes quotes and backslashes`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val model = "provider\\\\name\"v1"
        client().setSessionModel("s1", model)
        val body = server.takeRequest().body.readUtf8()
        val decoded = HermesJson.parseToJsonElement(body).jsonObject["model"]!!.jsonPrimitive.content
        assertEquals(model, decoded)
    }

    // 2. clearSessionModel("s1") → body EXACTLY {"model":null}
    @Test
    fun `clearSessionModel body exactly model null`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"object":"hermes.session.model_lock","session_id":"s1","automatic":true}"""))
        client().clearSessionModel("s1")
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertEquals("""{"model":null}""", body)
    }

    // 3. setSessionModel 400 → failure
    @Test
    fun `setSessionModel 400 returns failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        val result = client().setSessionModel("s1", "m1")
        assertTrue(result.isFailure)
    }

    // 4. ModelLockResponse with runtime decodes model
    @Test
    fun `ModelLockResponse runtime model decodes`() {
        val json = """{"object":"hermes.session.model_lock","session_id":"s1","automatic":false,"runtime":{"model":"m1","route_source":"session_model_lock"}}"""
        val resp = HermesJson.decodeFromString(ModelLockResponse.serializer(), json)
        assertEquals("m1", resp.runtime?.model)
        assertEquals("session_model_lock", resp.runtime?.route_source)
    }

    // 5. SessionEnvelope with runtime decodes
    @Test
    fun `SessionEnvelope with runtime decodes`() {
        val json = """{"session":{"id":"s1"},"runtime":{"model":"m2","route_source":"global"}}"""
        val env = HermesJson.decodeFromString(SessionEnvelope.serializer(), json)
        assertEquals("s1", env.session.id)
        assertEquals("m2", env.runtime?.model)
        assertEquals("global", env.runtime?.route_source)
    }
}
