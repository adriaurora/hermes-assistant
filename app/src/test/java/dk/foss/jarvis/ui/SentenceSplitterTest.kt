package dk.foss.jarvis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceSplitterTest {

    private fun takeAll(vararg chunks: String): List<String> {
        val buffer = StringBuilder()
        val out = mutableListOf<String>()
        for (c in chunks) {
            buffer.append(c)
            while (true) {
                val s = SentenceSplitter.takeNext(buffer) ?: break
                if (s.isNotEmpty()) out.add(s)
            }
        }
        return out
    }

    @Test
    fun `no complete sentence yet`() {
        val b = StringBuilder("hello wor")
        assertNull(SentenceSplitter.takeNext(b))
        assertEquals("hello wor", b.toString())
    }

    @Test
    fun `splits on period followed by whitespace`() {
        // The final "." stays buffered until more text arrives (mid-stream rule).
        assertEquals(listOf("Hi there.", "Next one."), takeAll("Hi there. Next one. And more"))
    }

    @Test
    fun `decimals are not split`() {
        assertNull(SentenceSplitter.takeNext(StringBuilder("Pi is 3.")))
        assertNull(SentenceSplitter.takeNext(StringBuilder("Pi is 3.5 ok")))
    }

    @Test
    fun `trailing period mid stream is not split`() {
        assertNull(SentenceSplitter.takeNext(StringBuilder("Done.")))
    }

    @Test
    fun `newline ends a sentence`() {
        assertEquals(listOf("Line one"), takeAll("Line one\nLine two"))
    }

    @Test
    fun `question and exclamation marks split`() {
        assertEquals(listOf("Really?", "Yes!"), takeAll("Really? Yes! Go on"))
    }

    @Test
    fun `soft cap starts a long clause at the last space`() {
        val long = "word ".repeat(50).trim() // 249 chars, spaces throughout
        val got = SentenceSplitter.takeNext(StringBuilder(long))
        // Must produce something (soft cap), not beyond position 40 start.
        assertEquals(true, got != null && got.length < long.length)
    }

    @Test
    fun `streaming across many deltas yields full sentences in order`() {
        assertEquals(
            listOf("One two.", "Three four."),
            takeAll("One ", "tw", "o. Thr", "ee four. tail"),
        )
    }

    // TTS dedup: delta then final text that starts with delta — second extraction
    // must yield only the new suffix, not re-extract already-spoken sentences.
    @Test
    fun `deltaThenFullText_secondExtraction_onlyNewSuffix`() {
        // Simulates: deltas give "Hello. How are you", then onFinalContent gives
        // "Hello. How are you today?" → only " today?" portion (if it forms a sentence)
        val buffer = StringBuilder()

        // Phase 1: deltas accumulate "Hello. How are you" (14 chars)
        buffer.append("Hello. How are you")
        val s1 = SentenceSplitter.takeNext(buffer)
        assertEquals("Hello.", s1)
        assertEquals(" How are you", buffer.toString())

        // Phase 2: more deltas " today? " (7 chars with space)
        buffer.append(" today? ")
        val s2 = SentenceSplitter.takeNext(buffer)
        assertEquals("How are you today?", s2)
        assertEquals(" ", buffer.toString())

        // deltaLen = 14 + 7 = 21 (chars from delta fragments)
        // fullText = "Hello. How are you today?" (25 chars)
        // suffix = fullText[21:] = "y?" — but that's not a full sentence boundary
        // The actual onFinalContent in ConversationViewModel does:
        //   sentenceBuffer.append(fullText, deltaLen, fullText.length)
        //   extractSentences()
        val fullText = "Hello. How are you today?"
        val deltaLen = 21
        if (deltaLen > 0 && deltaLen < fullText.length) {
            buffer.setLength(0)
            // Only append the portion NOT already in deltas
            buffer.append(fullText, deltaLen, fullText.length)
            // This appends "ay?" — no complete sentence to extract
            val s3 = SentenceSplitter.takeNext(buffer)
            assertNull("New suffix after delta coverage may not form complete sentence", s3)
        }
    }
}
