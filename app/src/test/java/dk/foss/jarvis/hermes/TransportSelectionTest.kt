package dk.foss.jarvis.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Kotlin tests for ChatTransportSelector.decide logic. No MockWebServer needed.
 */
class TransportSelectionTest {

    private fun decide(features: OriginCapabilities?, convTransport: ChatTransportKind?, convOrigin: String?, currentOrigin: String, hasMessages: Boolean) =
        ChatTransportSelector.decide(features, convTransport, convOrigin, currentOrigin, hasMessages)

    private fun isSessions(d: ChatTransportDecision): Boolean = d is ChatTransportDecision.Sessions
    private fun isLegacy(d: ChatTransportDecision): Boolean = d is ChatTransportDecision.Legacy
    private fun isBlocked(d: ChatTransportDecision): Boolean = d is ChatTransportDecision.Blocked
    private fun isUnavailable(d: ChatTransportDecision): Boolean = d is ChatTransportDecision.Unavailable

    // 1. undecided(null) + !hasMessages + SUPPORTED(session_chat=true) → Sessions with features
    @Test
    fun `undecided no messages supported session_chat yields Sessions`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true))
        val d = decide(features, null, null, "https://h:1", false)
        assertTrue(isSessions(d))
        val s = d as ChatTransportDecision.Sessions
        assertTrue(s.features.session_chat)
    }

    // 1b. with session_chat_streaming=true features carried
    @Test
    fun `undecided no messages supported carries streaming features`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true, session_chat_streaming = true))
        val d = decide(features, null, null, "https://h:1", false)
        assertTrue(isSessions(d))
        val s = d as ChatTransportDecision.Sessions
        assertTrue(s.features.session_chat_streaming)
    }

    // 2. undecided + !hasMessages + UNSUPPORTED(404) → Legacy
    @Test
    fun `undecided no messages unsupported yields Legacy`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNSUPPORTED)
        val d = decide(features, null, null, "https://h:1", false)
        assertTrue(isLegacy(d))
    }

    // 3. undecided + !hasMessages + UNKNOWN → Blocked, NOT Legacy
    @Test
    fun `undecided no messages unknown yields Blocked`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNKNOWN)
        val d = decide(features, null, null, "https://h:1", false)
        assertTrue(isBlocked(d))
        assertFalse(isLegacy(d))
    }

    // 3b. UNKNOWN + hasMessages → Blocked (fail-closed wins over sticky-legacy)
    // This is the key regression test: hasMessages must NOT bypass fail-closed when convTransport is null.
    @Test
    fun `undecided has messages unknown still yields Blocked_fail_closed_wins`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNKNOWN)
        val d = decide(features, null, null, "https://h:1", true)
        assertTrue(isBlocked(d))
        assertFalse(isLegacy(d))
    }

    // 4. undecided + hasMessages + SUPPORTED → Legacy (sticky-legacy, since caps confirmed)
    @Test
    fun `undecided has messages supported yields Legacy`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true))
        val d = decide(features, null, null, "https://h:1", true)
        assertTrue(isLegacy(d))
    }

    // 5. LEGACY_CHAT marker + SUPPORTED modern caps → Legacy (never upgrades)
    @Test
    fun `legacy marker with supported caps stays Legacy`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true))
        val d = decide(features, ChatTransportKind.LEGACY_CHAT, null, "https://h:1", false)
        assertTrue(isLegacy(d))
    }

    // 6. SESSIONS marker + convOrigin==currentOrigin → Sessions even when caps UNKNOWN
    @Test
    fun `sessions marker same origin yields Sessions even with unknown caps`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNKNOWN)
        val d = decide(features, ChatTransportKind.SESSIONS, "https://h:1", "https://h:1", false)
        assertTrue(isSessions(d))
    }

    // 7. SESSIONS marker + convOrigin!=currentOrigin → Unavailable
    @Test
    fun `sessions marker different origin yields Unavailable`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true))
        val d = decide(features, ChatTransportKind.SESSIONS, "https://other:1", "https://h:1", false)
        assertTrue(isUnavailable(d))
    }

    // 8. SUPPORTED but session_chat=false + undecided + !hasMessages → Legacy
    @Test
    fun `supported but session_chat_false yields Legacy`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = false, model_options = true))
        val d = decide(features, null, null, "https://h:1", false)
        assertTrue(isLegacy(d))
    }

    // 9. null features + undecided + !hasMessages → Blocked
    @Test
    fun `null features undecided no messages yields Blocked`() {
        val d = decide(null, null, null, "https://h:1", false)
        assertTrue(isBlocked(d))
    }

    // 10. LEGACY marker with messages → Legacy
    @Test
    fun `legacy marker with messages yields Legacy`() {
        val d = decide(null, ChatTransportKind.LEGACY_CHAT, null, "https://h:1", true)
        assertTrue(isLegacy(d))
    }

    // 11. UNSUPPORTED with hasMessages → Legacy (fallback from caps, not from hasMessages)
    @Test
    fun `undecided has messages unsupported yields Legacy`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNSUPPORTED)
        val d = decide(features, null, null, "https://h:1", true)
        assertTrue(isLegacy(d))
    }

    // 12. UNKNOWN without hasMessages → Blocked (no messages means no commitment yet)
    @Test
    fun `undecided no messages unknown no msgs yields Blocked`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNKNOWN)
        val d = decide(features, null, null, "https://h:1", false)
        assertTrue(isBlocked(d))
    }

    // 13. Fresh conversation (hasMessages=false) + modern caps → Sessions,
    //     NOT Legacy. This is the critical path for Task 1: decide transport
    //     BEFORE adding the first user message.
    @Test
    fun `freshConversation_modernCaps_decides_Sessions_not_Legacy`() {
        val features = OriginCapabilities(
            "https://h:1",
            CapabilityState.SUPPORTED,
            ServerFeatures(session_chat = true, session_chat_streaming = true, model_options = true),
        )
        val d = decide(features, null, null, "https://h:1", false)
        assertTrue(isSessions(d))
        val s = d as ChatTransportDecision.Sessions
        assertTrue(s.features.session_chat)
        assertTrue(s.features.session_chat_streaming)
    }

    // 14. 401/500 caps error → UNKNOWN → Blocked, NEVER Legacy
    @Test
    fun `capsError_401_unknown_yields_Blocked_not_Legacy`() {
        // Simulates a 401 from getCapabilities → CapabilityState.UNKNOWN
        val features = OriginCapabilities("https://h:1", CapabilityState.UNKNOWN)
        val d = decide(features, null, null, "https://h:1", false)
        assertTrue(isBlocked(d))
        assertFalse(isLegacy(d))
    }

    // 15. Network error caps → UNKNOWN → Blocked, NEVER Legacy
    @Test
    fun `capsNetworkError_unknown_yields_Blocked_not_Legacy`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNKNOWN)
        val d = decide(features, null, null, "https://h:1", false)
        assertTrue(isBlocked(d))
        assertFalse(isLegacy(d))
    }
}