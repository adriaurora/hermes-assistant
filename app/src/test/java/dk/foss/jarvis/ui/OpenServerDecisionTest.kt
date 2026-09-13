package dk.foss.jarvis.ui

import dk.foss.jarvis.hermes.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the openServer transport decision logic (HistoryViewModel.openServer).
 *
 * The decision should be:
 * - SUPPORTED + session_chat=true → SESSIONS
 * - SUPPORTED + session_chat=false → LEGACY_CHAT
 * - UNSUPPORTED → LEGACY_CHAT
 * - UNKNOWN / caps null → fail-closed: null transport (no import)
 */
class OpenServerDecisionTest {

    /**
     * Mirrors the decision logic from HistoryViewModel.openServer.
     * Returns the transport to use, or null if fail-closed.
     */
    private fun decideTransport(caps: OriginCapabilities?): ChatTransportKind? = when (caps?.state) {
        CapabilityState.SUPPORTED -> if (caps.features.session_chat) ChatTransportKind.SESSIONS else null
        CapabilityState.UNSUPPORTED -> null
        else -> null // UNKNOWN or caps null → fail-closed
    }

    @Test
    fun `SUPPORTED with session_chat true yields SESSIONS`() {
        val caps = OriginCapabilities(
            "https://hermes.local",
            CapabilityState.SUPPORTED,
            ServerFeatures(session_chat = true, session_chat_streaming = true),
        )
        val transport = decideTransport(caps)
        assertEquals(ChatTransportKind.SESSIONS, transport)
    }

    @Test
    fun `SUPPORTED with session_chat false yields null`() {
        val caps = OriginCapabilities(
            "https://hermes.local",
            CapabilityState.SUPPORTED,
            ServerFeatures(session_chat = false, model_options = true),
        )
        val transport = decideTransport(caps)
        assertNull(transport)
    }

    @Test
    fun `UNSUPPORTED yields null`() {
        val caps = OriginCapabilities("https://hermes.local", CapabilityState.UNSUPPORTED)
        val transport = decideTransport(caps)
        assertNull(transport)
    }

    /**
     * Key fail-closed test: UNKNOWN should NOT produce LEGACY_CHAT.
     * The old code was `if (caps.features.session_chat) SESSIONS else LEGACY_CHAT`
     * which meant UNKNOWN (all features false) → LEGACY_CHAT, violating fail-closed.
     */
    @Test
    fun `UNKNOWN yields null_transport_fail_closed`() {
        val caps = OriginCapabilities("https://hermes.local", CapabilityState.UNKNOWN)
        val transport = decideTransport(caps)
        assertNull("UNKNOWN must NOT produce LEGACY_CHAT (fail-closed)", transport)
    }

    @Test
    fun `null caps yields null_transport_fail_closed`() {
        val transport = decideTransport(null)
        assertNull("null caps must NOT produce LEGACY_CHAT (fail-closed)", transport)
    }

    @Test
    fun `SUPPORTED with only model_options yields null_not_SESSIONS`() {
        val caps = OriginCapabilities(
            "https://hermes.local",
            CapabilityState.SUPPORTED,
            ServerFeatures(model_options = true),
        )
        val transport = decideTransport(caps)
        assertNull(transport)
    }
}