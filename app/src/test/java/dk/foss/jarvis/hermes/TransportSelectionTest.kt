package dk.foss.jarvis.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Kotlin tests for ChatTransportSelector.decide logic. No MockWebServer needed.
 */
class TransportSelectionTest {

    private fun decide(features: OriginCapabilities?, convTransport: ChatTransportKind?, convOrigin: String?, currentOrigin: String) =
        ChatTransportSelector.decide(features, convTransport, convOrigin, currentOrigin)

    private fun isSessions(d: ChatTransportDecision): Boolean = d is ChatTransportDecision.Sessions
    private fun isBlocked(d: ChatTransportDecision): Boolean = d is ChatTransportDecision.Blocked
    private fun isUnavailable(d: ChatTransportDecision): Boolean = d is ChatTransportDecision.Unavailable

    // 1. undecided(null) + SUPPORTED(session_chat=true) → Sessions with features
    @Test
    fun `undecided supported session_chat yields Sessions`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true))
        val d = decide(features, null, null, "https://h:1")
        assertTrue(isSessions(d))
        val s = d as ChatTransportDecision.Sessions
        assertTrue(s.features.session_chat)
    }

    // 1b. with session_chat_streaming=true features carried
    @Test
    fun `undecided supported carries streaming features`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true, session_chat_streaming = true))
        val d = decide(features, null, null, "https://h:1")
        assertTrue(isSessions(d))
        val s = d as ChatTransportDecision.Sessions
        assertTrue(s.features.session_chat_streaming)
    }

    // 2. undecided + UNSUPPORTED(404) → Blocked
    @Test
    fun `undecided unsupported yields Blocked`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNSUPPORTED)
        val d = decide(features, null, null, "https://h:1")
        assertTrue(isBlocked(d))
    }

    // 3. undecided + UNKNOWN → Blocked, NOT Legacy
    @Test
    fun `undecided unknown yields Blocked`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNKNOWN)
        val d = decide(features, null, null, "https://h:1")
        assertTrue(isBlocked(d))
        assertFalse(isSessions(d))
    }

    // 4. undecided + SUPPORTED → Sessions (session_chat=true)
    @Test
    fun `undecided supported yields Sessions`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true))
        val d = decide(features, null, null, "https://h:1")
        assertTrue(isSessions(d))
    }

    // 5. LEGACY_CHAT marker + SUPPORTED modern caps → Unavailable (legacy chat not supported)
    @Test
    fun `legacy marker with supported caps yields Unavailable`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true))
        val d = decide(features, ChatTransportKind.LEGACY_CHAT, null, "https://h:1")
        assertTrue(isUnavailable(d))
    }

    // 6. SESSIONS marker + convOrigin==currentOrigin + SUPPORTED caps → Sessions
    @Test
    fun `sessions marker same origin supported yields Sessions`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true))
        val d = decide(features, ChatTransportKind.SESSIONS, "https://h:1", "https://h:1")
        assertTrue(isSessions(d))
    }

    // 6b. SESSIONS marker + convOrigin==currentOrigin + UNKNOWN caps → Blocked
    @Test
    fun `sessions marker same origin unknown yields Blocked`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNKNOWN)
        val d = decide(features, ChatTransportKind.SESSIONS, "https://h:1", "https://h:1")
        assertTrue(isBlocked(d))
    }

    // 7. SESSIONS marker + convOrigin!=currentOrigin → Unavailable
    @Test
    fun `sessions marker different origin yields Unavailable`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true))
        val d = decide(features, ChatTransportKind.SESSIONS, "https://other:1", "https://h:1")
        assertTrue(isUnavailable(d))
    }

    // 8. SUPPORTED but session_chat=false + undecided → Blocked
    @Test
    fun `supported but session_chat_false yields Blocked`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = false, model_options = true))
        val d = decide(features, null, null, "https://h:1")
        assertTrue(isBlocked(d))
    }

    // 9. null features + undecided → Blocked
    @Test
    fun `null features undecided yields Blocked`() {
        val d = decide(null, null, null, "https://h:1")
        assertTrue(isBlocked(d))
    }

    // 10. LEGACY marker → Unavailable
    @Test
    fun `legacy marker yields Unavailable`() {
        val d = decide(null, ChatTransportKind.LEGACY_CHAT, null, "https://h:1")
        assertTrue(isUnavailable(d))
    }

    // 11. UNSUPPORTED with messages → Blocked (not Legacy)
    @Test
    fun `undecided has messages unsupported yields Blocked`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNSUPPORTED)
        val d = decide(features, null, null, "https://h:1")
        assertTrue(isBlocked(d))
    }

    // 12. UNKNOWN without messages → Blocked (no messages means no commitment yet)
    @Test
    fun `undecided no messages unknown yields Blocked`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNKNOWN)
        val d = decide(features, null, null, "https://h:1")
        assertTrue(isBlocked(d))
    }

    // 13. Fresh conversation + modern caps → Sessions
    @Test
    fun `freshConversation_modernCaps_decides_Sessions_not_Legacy`() {
        val features = OriginCapabilities(
            "https://h:1",
            CapabilityState.SUPPORTED,
            ServerFeatures(session_chat = true, session_chat_streaming = true, model_options = true),
        )
        val d = decide(features, null, null, "https://h:1")
        assertTrue(isSessions(d))
        val s = d as ChatTransportDecision.Sessions
        assertTrue(s.features.session_chat)
        assertTrue(s.features.session_chat_streaming)
    }

    // 14. 401/500 caps error → UNKNOWN → Blocked, NEVER Legacy
    @Test
    fun `capsError_401_unknown_yields_Blocked_not_Legacy`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNKNOWN)
        val d = decide(features, null, null, "https://h:1")
        assertTrue(isBlocked(d))
    }

    // 15. Network error caps → UNKNOWN → Blocked, NEVER Legacy
    @Test
    fun `capsNetworkError_unknown_yields_Blocked_not_Legacy`() {
        val features = OriginCapabilities("https://h:1", CapabilityState.UNKNOWN)
        val d = decide(features, null, null, "https://h:1")
        assertTrue(isBlocked(d))
    }
}