package dk.foss.jarvis.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatErrorSemanticsTest {
    @Test fun `auth errors use stable product wording`() {
        assertEquals(
            "Authentication failed. Check the API key in Settings.",
            semanticChatError(HermesHttpError(401, null, "secret response", "raw")),
        )
    }

    @Test fun `cancellation has no user-facing error`() {
        assertEquals("", semanticChatError(java.util.concurrent.CancellationException()))
    }

    @Test fun `incomplete stream asks for reconciliation without retry`() {
        assertEquals(
            "Response stream ended before completion. Open the conversation to reconcile its latest server history.",
            semanticChatError(StreamClosedBeforeTerminalError()),
        )
    }
}
