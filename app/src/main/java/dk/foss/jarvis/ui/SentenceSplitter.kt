package dk.foss.jarvis.ui

/**
 * Pure sentence-splitting rules for the streaming TTS pipeline: pull the next
 * complete sentence off the front of the growing response text so speech can
 * start before the stream finishes.
 *
 * Rules (behavior preserved from the original ConversationViewModel logic):
 * - a sentence ends at '\n', or at '.'/'!'/'?' ONLY when followed by
 *   whitespace (so "3.5" and a trailing mid-stream "." don't split);
 * - soft cap: one very long clause (>180 chars) starts speaking at the last
 *   space beyond position 40;
 * - null means "no complete sentence yet".
 */
object SentenceSplitter {

    const val SOFT_CAP_CHARS = 180
    const val MIN_SOFT_CUT_INDEX = 40

    /**
     * Removes and returns the next complete sentence from [buffer] (trimmed),
     * or null when no full sentence is available yet. The returned string may
     * be empty (e.g. a lone "\n") — callers must skip empty results; the
     * consumed input is discarded either way.
     */
    fun takeNext(buffer: StringBuilder): String? {
        var cut = -1
        for (i in buffer.indices) {
            val c = buffer[i]
            if (c == '\n') { cut = i; break }
            if ((c == '.' || c == '!' || c == '?') &&
                i + 1 < buffer.length && buffer[i + 1].isWhitespace()
            ) { cut = i; break }
        }
        if (cut < 0 && buffer.length > SOFT_CAP_CHARS) {
            val sp = buffer.lastIndexOf(' ')
            if (sp > MIN_SOFT_CUT_INDEX) cut = sp
        }
        if (cut < 0) return null
        val sentence = buffer.substring(0, cut + 1).trim()
        buffer.delete(0, cut + 1)
        return sentence
    }
}
