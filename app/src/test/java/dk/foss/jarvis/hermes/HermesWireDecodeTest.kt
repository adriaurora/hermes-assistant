package dk.foss.jarvis.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decode of the server surfaces added for history parity and model
 * groundwork, using shapes verified against the installed api_server
 * (`_session_response`, `_message_response`, `build_models_payload`).
 */
class HermesWireDecodeTest {

    @Test
    fun `sessions page decodes with epoch-float timestamps and unknown keys`() {
        val json = """
            {"object":"list","has_more":true,"limit":50,"offset":0,
             "data":[
               {"id":"20260822_071420_b2b62193","source":"telegram","title":"Cambios SEO",
                "started_at":1787382860.47,"ended_at":1787382900.1,"last_active":1787382960.9,
                "message_count":12,"pinned":false,"hidden":false,"input_tokens":99,
                "preview":"first message text"},
               {"id":"srv2","source":"api_server","started_at":"2026-08-22T07:14:20Z",
                "message_count":0}
             ]}
        """.trimIndent()
        val page = HermesJson.decodeFromString(SessionsPage.serializer(), json)
        assertTrue(page.has_more)
        assertEquals(2, page.data.size)
        val first = page.data[0]
        assertEquals("telegram", first.source)
        assertEquals(1787382860470L, first.started_at)
        assertEquals(1787382960900L, first.last_active)
        assertEquals(12, first.message_count)
        assertFalse(first.pinned)
        // ISO string form also accepted; missing optional fields default.
        val second = page.data[1]
        assertEquals(1787382860000L, second.started_at)
        assertNull(second.last_active)
    }

    @Test
    fun `session messages page keeps raw roles including internal ones`() {
        val json = """
            {"object":"list","session_id":"s1",
             "data":[
               {"role":"user","content":"hola","timestamp":1787382860.5},
               {"role":"tool","content":"{\"ok\":true}","tool_name":"confirm_bufanatic_execution"},
               {"role":"assistant","content":"hecho","reasoning_content":"internal"}
             ]}
        """.trimIndent()
        val page = HermesJson.decodeFromString(SessionMessagesPage.serializer(), json)
        assertEquals("s1", page.session_id)
        assertEquals(3, page.data.size)
        assertEquals("user", page.data[0].role)
        assertEquals("tool", page.data[1].role)
        assertEquals(1787382860500L, page.data[0].timestamp)
    }

    @Test
    fun `tool progress frame decodes`() {
        val p = HermesJson.decodeFromString(
            ToolProgress.serializer(),
            """{"tool":"web_search","emoji":"x","label":"Searching the web","status":"running","toolCallId":"t1"}""",
        )
        assertEquals("web_search", p.tool)
        assertEquals("Searching the web", p.label)
        assertTrue(p.status.equals("running", ignoreCase = true))
    }

    @Test
    fun `model options payload decodes tolerantly`() {
        val json = """
            {"providers":[
               {"slug":"custom","name":"Custom provider","is_current":true,
                "models":["mimo-v2.5"],"total_models":1,"authenticated":true,
                "auth_type":"api_key"},
               {"slug":"openai","name":"OpenAI","is_current":false,"models":[],
                "total_models":0}],
             "model":"mimo-v2.5","provider":"custom"}
        """.trimIndent()
        val payload = HermesJson.decodeFromString(ModelOptionsPayload.serializer(), json)
        assertEquals("mimo-v2.5", payload.model)
        assertEquals("custom", payload.provider)
        assertEquals(2, payload.providers.size)
        assertEquals(listOf("mimo-v2.5"), payload.providers[0].models)
        assertTrue(payload.providers[0].is_current)
    }

    @Test
    fun `session envelope exposes the currently pinned model`() {
        val json = """{"session":{"id":"20260822_071420_b2b62193","source":"api","model":"qwen3.6","message_count":4}}"""
        val env = HermesJson.decodeFromString(SessionEnvelope.serializer(), json)
        assertEquals("20260822_071420_b2b62193", env.session.id)
        assertEquals("qwen3.6", env.session.model)
        assertEquals("api", env.session.source)
    }

    @Test
    fun `model lock response decodes with automatic flag`() {
        val json = """{"object":"hermes.session.model_lock","session_id":"s1","runtime":{"provider":"","model":"","route_source":"raw_request","model_lock":"accepted"},"automatic":true}"""
        val resp = HermesJson.decodeFromString(ModelLockResponse.serializer(), json)
        assertEquals("s1", resp.session_id)
        assertTrue(resp.automatic)
        assertEquals("hermes.session.model_lock", resp.`object`)
    }

    @Test
    fun `clear model response decodes the live automatic release shape`() {
        // Exact body returned by the patched server (v0.3.1) after a clear:
        // runtime is empty and automatic signals that session.model was
        // removed (the override is gone, not frozen to a default).
        val json = """{"object":"hermes.session.model_lock","session_id":"s2","runtime":{},"automatic":true}"""
        val resp = HermesJson.decodeFromString(ModelLockResponse.serializer(), json)
        assertEquals("s2", resp.session_id)
        assertTrue(resp.automatic)
        assertEquals("hermes.session.model_lock", resp.`object`)
    }
}
