package dk.foss.jarvis.hermes

import org.junit.Assert.assertTrue
import org.junit.Test

/** Sessions-only capability and origin gate matrix. */
class TransportSelectionTest {
    private fun decide(f: OriginCapabilities?, t: ChatTransportKind? = null, origin: String? = null) =
        ChatTransportSelector.decide(f, t, origin, "https://h:1")

    @Test fun `supported session chat selects sessions`() {
        assertTrue(decide(OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true))) is ChatTransportDecision.Sessions)
    }
    @Test fun `unknown is blocked`() {
        assertTrue(decide(OriginCapabilities("https://h:1", CapabilityState.UNKNOWN)) is ChatTransportDecision.Blocked)
        assertTrue(decide(null) is ChatTransportDecision.Blocked)
    }
    @Test fun `unsupported is blocked without fallback`() {
        assertTrue(decide(OriginCapabilities("https://h:1", CapabilityState.UNSUPPORTED)) is ChatTransportDecision.Blocked)
    }
    @Test fun `explicit session false is blocked or unavailable`() {
        val d = decide(OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = false)))
        assertTrue(d is ChatTransportDecision.Blocked || d is ChatTransportDecision.Unavailable)
    }
    @Test fun `stored legacy is unavailable`() {
        assertTrue(decide(OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true)), ChatTransportKind.LEGACY_CHAT) is ChatTransportDecision.Unavailable)
    }
    @Test fun `stored sessions still gates capability and origin`() {
        assertTrue(decide(OriginCapabilities("https://h:1", CapabilityState.UNKNOWN), ChatTransportKind.SESSIONS, "https://h:1") is ChatTransportDecision.Blocked)
        assertTrue(decide(OriginCapabilities("https://h:1", CapabilityState.SUPPORTED, ServerFeatures(session_chat = true)), ChatTransportKind.SESSIONS, "https://other:1") is ChatTransportDecision.Unavailable)
    }
}
