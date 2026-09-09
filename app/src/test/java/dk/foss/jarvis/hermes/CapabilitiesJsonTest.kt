package dk.foss.jarvis.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests parseCapabilities across all known input shapes.
 */
class CapabilitiesJsonTest {

    private fun allOtherFalse(f: ServerFeatures) =
        !f.session_chat_streaming && !f.session_model_lock &&
            !f.session_model_clear && !f.model_options && !f.chat_completions

    // 1. Full envelope with unknown keys
    @Test
    fun `full envelope with unknown keys parses all session flags true`() {
        val json = """
            {"object":"hermes.api_server.capabilities","platform":"hermes-agent","model":"hermes-agent",
             "auth":{"type":"bearer","required":true},
             "features":{
               "session_chat":true,"session_chat_streaming":true,"session_model_lock":true,
               "session_model_clear":true,"model_options":true,"chat_completions":true,
               "unknown_flag":true}
            }
        """.trimIndent()
        val features = parseCapabilities(json)
        assertTrue(features.session_chat)
        assertTrue(features.session_chat_streaming)
        assertTrue(features.session_model_lock)
        assertTrue(features.session_model_clear)
        assertTrue(features.model_options)
    }

    // 2. Minimal envelope
    @Test
    fun `minimal envelope with only session_chat true`() {
        val json = """{"features":{"session_chat":true}}"""
        val features = parseCapabilities(json)
        assertTrue(features.session_chat)
        assertTrue(allOtherFalse(features))
    }

    // 3. Flat shape
    @Test
    fun `flat shape parses session_chat and model_options true`() {
        val json = """{"session_chat":true,"model_options":true}"""
        val features = parseCapabilities(json)
        assertTrue(features.session_chat)
        assertTrue(features.model_options)
    }

    // 4. Empty object
    @Test
    fun `empty object yields all false no exception`() {
        val features = parseCapabilities("{}")
        assertTrue(allOtherFalse(features))
    }

    // 5. Garbage
    @Test
    fun `garbage yields all false no exception`() {
        val features = parseCapabilities("garbage {")
        assertTrue(allOtherFalse(features))
    }

    // 6. Empty string
    @Test
    fun `empty string yields all false no exception`() {
        val features = parseCapabilities("")
        assertTrue(allOtherFalse(features))
    }

    // 7. Full envelope with all false (unknown keys still tolerated)
    @Test
    fun `full envelope with all false features`() {
        val json = """
            {"object":"hermes.api_server.capabilities",
             "features":{
               "session_chat":false,"session_chat_streaming":false,"session_model_lock":false,
               "session_model_clear":false,"model_options":false,"chat_completions":false}
            }
        """.trimIndent()
        val features = parseCapabilities(json)
        assertFalse(features.session_chat)
        assertFalse(features.session_chat_streaming)
        assertFalse(features.session_model_lock)
        assertFalse(features.session_model_clear)
        assertFalse(features.model_options)
        assertFalse(features.chat_completions)
    }

    // 8. Only model_options true
    @Test
    fun `only model_options true`() {
        val json = """{"features":{"model_options":true}}"""
        val features = parseCapabilities(json)
        assertTrue(features.model_options)
        assertFalse(features.session_chat)
        assertFalse(features.session_chat_streaming)
        assertFalse(features.session_model_lock)
        assertFalse(features.session_model_clear)
        assertFalse(features.chat_completions)
    }

    // 9. chat_completions true
    @Test
    fun `chat_completions true`() {
        val json = """{"features":{"chat_completions":true}}"""
        val features = parseCapabilities(json)
        assertTrue(features.chat_completions)
        assertFalse(features.session_chat)
        assertFalse(features.session_chat_streaming)
        assertFalse(features.session_model_lock)
        assertFalse(features.session_model_clear)
        assertFalse(features.model_options)
    }
}