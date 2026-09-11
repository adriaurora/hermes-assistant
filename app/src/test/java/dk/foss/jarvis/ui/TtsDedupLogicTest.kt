package dk.foss.jarvis.ui

import org.junit.Assert.*
import org.junit.Test

/** Boundary tests for the final-content/TTS streamed-text reconciliation. */
class TtsDedupLogicTest {
    private fun computeTextForTts(finalText: String, streamed: String): String? =
        if (finalText.startsWith(streamed)) finalText.substring(streamed.length) else null

    @Test
    fun `tts_pendingPrefix_plus_finalSuffix_noLoss_noRepeat`() {
        assertEquals("ld.", computeTextForTts("Hello world.", "Hello wor"))
    }

    @Test
    fun `tts_finalEqualsStreamed_noReEnqueue`() {
        assertEquals("", computeTextForTts("Hi. There.", "Hi. There."))
    }

    @Test
    fun `noDeltas_fullTextIsEnqueued`() {
        assertEquals("Hello. How are you?", computeTextForTts("Hello. How are you?", ""))
    }

    @Test
    fun `divergentFinal_doesNotReplacePendingBuffer`() {
        assertNull(computeTextForTts("Different final", "Hello wor"))
    }
}
