package dk.foss.jarvis.ui

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for TTS sentence-dedup logic that ConversationViewModel applies when
 * onFinalContent arrives after delta fragments.
 *
 * The invariant: onFinalContent must enqueue speech only for the text NOT
 * already processed by delta fragments (tracked via deltaLen).
 *
 * We test the pure function at the boundary level since ConversationViewModel
 * is Android-dependent.
 */
class TtsDedupLogicTest {

    /**
     * Mirror of the onFinalContent TTS-dedup logic from ConversationViewModel.
     * Returns the text that should be appended to sentenceBuffer for extraction.
     */
    private fun computeTextForTts(fullText: String, deltaLen: Int): String? {
        return when {
            deltaLen == 0 -> fullText  // no deltas → full text
            deltaLen < fullText.length -> fullText.substring(deltaLen)
            else -> null  // deltas already covered everything
        }
    }

    // 1. No deltas → full text must be spoken
    @Test
    fun `noDeltas_fullTextIsEnqueued`() {
        val result = computeTextForTts("Hello. How are you?", 0)
        assertEquals("Hello. How are you?", result)
    }

    // 2. Delta covered same length → nothing to enqueue
    @Test
    fun `deltaLenEqualToText_nothingEnqueued`() {
        val deltaText = "Hello. How are you?"
        val result = computeTextForTts(deltaText, deltaText.length)
        assertNull("When delta covered the full text, nothing new should be enqueued", result)
    }

    // 3. Delta covered less → only the suffix is enqueued
    @Test
    fun `deltaShorterThanText_suffixEnqueued`() {
        val fullText = "Hello. How are you? I am fine."
        val deltaLen = 20 // "Hello. How are you? "
        val result = computeTextForTts(fullText, deltaLen)
        assertEquals("I am fine.", result)
    }

    // 4. Non-streaming: deltaLen=0 always gives full text
    @Test
    fun `nonStreaming_deltaLenZero_fullTextEnqueued`() {
        // Simulates sendSessionTurnNonStreaming which has deltaLen=0
        val result = computeTextForTts("I am fine.", 0)
        assertEquals("I am fine.", result)
    }

    // 5. Short final content when deltas are longer → nothing enqueued
    @Test
    fun `finalContentShorterThanDelta_nothingEnqueued`() {
        val fullText = "Hi"
        val deltaLen = 50 // deltas produced more than the final content
        val result = computeTextForTts(fullText, deltaLen)
        assertNull("When deltas are longer, nothing new should be enqueued", result)
    }

    // 6. Empty final content → nothing to enqueue regardless of deltas
    @Test
    fun `emptyFinalContent_nothingEnqueued`() {
        val result = computeTextForTts("", 0)
        assertEquals("", result)
    }
}