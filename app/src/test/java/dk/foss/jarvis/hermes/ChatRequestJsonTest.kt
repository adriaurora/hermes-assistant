package dk.foss.jarvis.hermes

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact wire format Hermes receives. Model omission is the core
 * contract: hermes-agent v0.20.4 treats an absent `model` as "use the
 * session/default model" (api_server.py `_request_agent_overrides`).
 */
class ChatRequestJsonTest {

    @Test
    fun `null model is omitted from the request body`() {
        val body = HermesJson.encodeToString(
            ChatRequest(messages = listOf(ChatMessage("user", "hello"))),
        )
        val obj = HermesJson.parseToJsonElement(body).jsonObject
        assertFalse("model must not be present when null", obj.containsKey("model"))
        assertTrue(obj.getValue("stream").jsonPrimitive.content == "true")
        assertEquals(1, obj.getValue("messages").jsonArray.size)
        val msg = obj.getValue("messages").jsonArray[0].jsonObject
        assertEquals("user", msg.getValue("role").jsonPrimitive.content)
        assertEquals("hello", msg.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `explicit model is sent when provided`() {
        val body = HermesJson.encodeToString(
            ChatRequest(model = "hermes-agent", messages = emptyList()),
        )
        val obj = HermesJson.parseToJsonElement(body).jsonObject
        assertEquals("hermes-agent", obj.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun `unknown server fields are ignored on decode`() {
        val chunk = HermesJson.decodeFromString(
            StreamChunk.serializer(),
            """{"choices":[{"delta":{"content":"Hi"},"finish_reason":null,"weird_field":1}],"usage":{"total_tokens":5}}""",
        )
        assertEquals("Hi", chunk.choices.single().delta.content)
    }
}
