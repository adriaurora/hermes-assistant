package dk.foss.jarvis.ui

import org.junit.Assert.*
import org.junit.Test

class TerminalSpeechTest {
    @Test fun `short final response without punctuation is spoken once`() {
        val buffer = StringBuilder("Hello there")
        assertNull(SentenceSplitter.takeNext(buffer))
        assertEquals("Hello there", SentenceSplitter.drainRemainder(buffer))
        assertEquals("", SentenceSplitter.drainRemainder(buffer))
    }
    @Test fun `last sentence follows complete sentences without being lost`() {
        val buffer = StringBuilder("First sentence. Remaining words")
        assertEquals("First sentence.", SentenceSplitter.takeNext(buffer))
        assertNull(SentenceSplitter.takeNext(buffer))
        assertEquals("Remaining words", SentenceSplitter.drainRemainder(buffer))
        assertTrue(buffer.isEmpty())
    }
}
