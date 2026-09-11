package dk.foss.jarvis.hermes

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that originIdentity called from ChatViewModel/ConversationViewModel
 * with an apiKey produces an identity that includes the path AND key hash.
 *
 * This is the format stored in Conversation.origin and compared in
 * ChatTransportSelector and conversation origin identity handling.
 */
class ChatViewModelOriginIdentityTest {

    /**
     * Simulates the call used by the chat send paths
     * now make: originIdentity(s.baseUrl, s.apiKey)
     */
    @Test
    fun `ChatViewModel_calls_originIdentity_with_apiKey_include_path_and_hash`() {
        val baseUrl = "https://hermes.example.com/api/chat"
        val apiKey = "secret-api-key-123"
        val identity = originIdentity(baseUrl, apiKey)

        // Must include the path component
        assertTrue("Identity must include the URL path", identity.contains("/api/chat"))

        // Must include the API key hash (hex chars after a dash)
        assertTrue("Identity must include the key hash suffix", identity.contains("-"))
        // The hash portion should be hex characters
        val hashPart = identity.substringAfterLast("-")
        assertTrue("Hash must be hex characters", hashPart.matches(Regex("[0-9a-f]+")))
    }

    /**
     * Simulates the call that ConversationViewModel.think and
     * ConversationViewModel.sendSessions (after Task 4 migration) make.
     */
    @Test
    fun `ConversationViewModel_originIdentity_includes_path_and_key`() {
        val baseUrl = "https://hermes.local/v1"
        val apiKey = "hermes-bearer-token"
        val identity = originIdentity(baseUrl, apiKey)

        assertTrue("Identity must include path", identity.contains("/v1"))
        assertTrue("Identity must include key hash", identity.contains("-"))

        // Different apiKeys must produce different identities (even on same URL)
        val identity2 = originIdentity(baseUrl, "different-key")
        assertNotEquals("Different keys must produce different identities", identity, identity2)
    }

    /**
     * Legacy code path (if still used somewhere): originIdentity(baseUrl) without apiKey.
     * This should still work (backward compat) but produce identity WITHOUT key hash.
     */
    @Test
    fun `originIdentity_without_apiKey_no_hash_suffix`() {
        val identity = originIdentity("https://hermes.example.com/api/chat")
        assertFalse("Identity without key must NOT contain a dash-hash suffix",
            identity.contains("-") && identity.substringAfterLast("-").matches(Regex("[0-9a-f]{16,}")))
    }

    /**
     * Verify that the new format is NOT equal to the legacy format, ensuring
     * that migrated conversations can be distinguished.
     */
    @Test
    fun `new_origin_identity_differs_from_legacy_same_url`() {
        val baseUrl = "https://hermes.example.com/api/chat"
        val apiKey = "key-123"
        val newIdentity = originIdentity(baseUrl, apiKey)
        val legacyIdentity = legacyOriginIdentity(baseUrl)

        assertNotEquals("New identity must differ from legacy (different format)", newIdentity, legacyIdentity)
        // New includes path, legacy does not
        assertTrue("New includes path", newIdentity.contains("/api/chat"))
        // Legacy is scheme://host:port
        assertTrue("Legacy is host-based", legacyIdentity.startsWith("https://hermes.example.com:443"))
    }
}
