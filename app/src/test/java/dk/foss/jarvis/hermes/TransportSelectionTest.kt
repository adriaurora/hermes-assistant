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

    // 4. undecided + hasMessages → Legacy regardless of caps
    @Test
    fun `undecided has messages yields Legacy regardless of caps`() {
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
}