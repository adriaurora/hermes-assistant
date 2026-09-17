package dk.foss.jarvis.ui

import dk.foss.jarvis.data.ConversationMeta
import dk.foss.jarvis.hermes.ChatTransportKind
import org.junit.Assert.*
import org.junit.Test

class HistorySessionIdentityTest {
    private fun mirror(local: String, session: String, origin: String) =
        ConversationMeta(local, "Title", 0, 1, session, ChatTransportKind.SESSIONS, origin)

    @Test fun `server session resolves to local UUID`() {
        assertEquals("local-uuid", findLocalSessionMirror(listOf(mirror("local-uuid", "server-id", "origin")), "server-id", "origin"))
    }
    @Test fun `same session identifier on another origin is not a match`() {
        assertNull(findLocalSessionMirror(listOf(mirror("uuid", "sid", "other")), "sid", "current"))
    }
    @Test fun `local filename resembling server identifier is not a match`() {
        assertNull(findLocalSessionMirror(listOf(mirror("sid", "different", "origin")), "sid", "origin"))
    }
    @Test fun `server only session requires import`() {
        assertNull(findLocalSessionMirror(emptyList(), "sid", "origin"))
    }
}
