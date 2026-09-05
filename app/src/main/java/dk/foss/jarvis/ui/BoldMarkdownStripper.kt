package dk.foss.jarvis.ui

/**
 * Strips every Markdown bold delimiter (`**`) from [text] so the TTS engine
 * does not vocalise them.  All other Markdown (headings, lists, inline code,
 * etc.) is left untouched.
 *
 * Example: `"**Hello** world" → "Hello world"`
 *
 * Uses plain string [String.replace] so unmatched asterisks and multi-line
 * bold blocks are also handled — every literal `**` is removed.
 */
object BoldMarkdownStripper {

    /**
     * Remove every literal `**` occurrence regardless of matching context.
     *
     * If the result is blank (empty or whitespace only) the caller should skip
     * TTS to avoid a useless engine invocation and stalling the queue.
     */
    fun strip(text: String): String = text.replace("**", "")
}