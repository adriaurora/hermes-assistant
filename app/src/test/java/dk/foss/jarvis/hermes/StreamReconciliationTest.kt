package dk.foss.jarvis.hermes

import org.junit.Assert.*
import org.junit.Test

class StreamReconciliationTest {
    private fun user(s: String) = ChatMessage("user", s)
    private fun answer(s: String) = ChatMessage("assistant", s)
    @Test fun `previous answer is not attached to failed new message`() {
        assertNull(reconciledAnswer(listOf(user("old"), answer("old answer"), user("new")), listOf(user("old"), answer("old answer"))))
    }
    @Test fun `matching completed turn is recovered`() {
        assertEquals("new answer", reconciledAnswer(listOf(user("old"), answer("old answer"), user("new")), listOf(user("old"), answer("old answer"), user("new"), answer("new answer"))))
    }
    @Test fun `another client turn is not attributed to this client`() {
        assertNull(reconciledAnswer(listOf(user("mine")), listOf(user("mine"), answer("a"), user("other"), answer("b"))))
    }
    @Test fun `repeated identical user text still needs full prefix`() {
        assertNull(reconciledAnswer(listOf(user("same"), answer("first"), user("same")), listOf(user("same"), answer("first"))))
    }
    @Test fun `truncated history remains uncertain`() {
        assertNull(reconciledAnswer(listOf(user("first"), answer("first answer"), user("last")), listOf(user("last"), answer("last answer"))))
    }
}
