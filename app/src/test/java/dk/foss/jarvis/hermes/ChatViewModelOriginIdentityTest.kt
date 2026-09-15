package dk.foss.jarvis.hermes

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that originIdentity is URL-only canonical identity.
 * API key is NOT included — conversations on the same host with different
 * keys share the same canonical identity (URL-only).
 *
 * This is the format stored in Conversation.origin and compared in
 * ChatTransportSelector and conversation origin identity handling.
 */
class ChatViewModelOriginIdentityTest {

    /**
     * originIdentity with apiKey does NOT include the key — identity is URL-only.
     */
    @Test
    fun `ChatViewModel_originIdentity_is_url_only_no_hash`() {
        val baseUrl = "https://hermes.example.com/api/chat"
        val apiKey = "secret-api-key-123"
        val identity = originIdentity(baseUrl, apiKey)

        // Must include the path component
        assertTrue("Identity must include the URL path", identity.contains("/api/chat"))

        // Must NOT include any key hash — identity is URL-only
        // The identity should be exactly the URL parts, no dash-hash suffix
        assertEquals("https://hermes.example.com/api/chat", identity)
    }

    /**
     * Different apiKeys on the same URL produce the SAME identity (URL-only).
     */
    @Test
    fun `ConversationViewModel_originIdentity_url_only_same_key_different`() {
        val baseUrl = "https://hermes.local/v1"
        val identity = originIdentity(baseUrl, "hermes-bearer-token")
        val identity2 = originIdentity(baseUrl, "different-key")
        val identity3 = originIdentity(baseUrl, null)

        // All must be equal — identity is URL-only
        assertEquals(identity, identity2)
        assertEquals(identity2, identity3)

        assertTrue("Identity must include path", identity.contains("/v1"))
    }

    /**
     * originIdentity(baseUrl) without apiKey still works (backward compat)
     * and produces the same URL-only identity.
     */
    @Test
    fun `originIdentity_without_apiKey_no_hash_suffix`() {
        val identity = originIdentity("https://hermes.example.com/api/chat")
        // Must not contain a dash-hash suffix
        assertFalse("Identity without key must NOT contain a dash-hash suffix",
            identity.contains("-") && identity.substringAfterLast("-").matches(Regex("[0-9a-f]{16,}")))
    }

    /**
     * Path differences produce different identities.
     */
    @Test
    fun `path_differences_produce_different_identity`() {
        val id1 = originIdentity("https://hermes.local/v1", "key-a")
        val id2 = originIdentity("https://hermes.local/v2", "key-b")
        assertEquals("https://hermes.local/v1", id1)
        assertEquals("https://hermes.local/v2", id2)
        assertNotEquals(id1, id2)
    }
}