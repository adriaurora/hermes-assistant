package dk.foss.jarvis.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BoldMarkdownStripperTest {

    @Test
    fun `strips matched bold markers`() {
        assertEquals("Hello", BoldMarkdownStripper.strip("**Hello**"))
    }

    @Test
    fun `strips multiple bold occurrences`() {
        assertEquals("A and B", BoldMarkdownStripper.strip("**A** and **B**"))
    }

    @Test
    fun `plain text returns unchanged`() {
        assertEquals("no markdown here", BoldMarkdownStripper.strip("no markdown here"))
    }

    @Test
    fun `strips bold inside longer sentence`() {
        assertEquals(
            "She said hello and left",
            BoldMarkdownStripper.strip("She said **hello** and left"),
        )
    }

    @Test
    fun `preserves other markdown`() {
        assertEquals("# Heading\n- item\n`code`", BoldMarkdownStripper.strip("# Heading\n- item\n`code`"))
    }

    @Test
    fun `strips unmatched opening asterisks`() {
        // space-**  space  →  two spaces remain
        assertEquals("hello  world", BoldMarkdownStripper.strip("hello ** world"))
        // space-**word  →  single space remains
        assertEquals("hello world", BoldMarkdownStripper.strip("hello **world"))
    }

    @Test
    fun `strips unmatched closing asterisks`() {
        assertEquals("hello world ", BoldMarkdownStripper.strip("hello world **"))
    }

    @Test
    fun `strips only asterisks from bold-only text`() {
        assertEquals("", BoldMarkdownStripper.strip("**"))
        // *** → the first ** is removed, leaving a single *
        assertEquals("*", BoldMarkdownStripper.strip("***"))
    }

    @Test
    fun `strips multi-line bold blocks`() {
        assertEquals("line1\nline2", BoldMarkdownStripper.strip("**line1\nline2**"))
    }

    @Test
    fun `mixed matched unmatched and multi-line`() {
        assertEquals(
            "a\nb and c",
            BoldMarkdownStripper.strip("**a\nb** and **c"),
        )
    }

    @Test
    fun `result is non-blank after stripping bold with surrounding spaces`() {
        val result = BoldMarkdownStripper.strip("  **bold**  ")
        assertEquals("  bold  ", result)
        assertEquals(true, result.isNotBlank())
    }

    @Test
    fun `multiple bold blocks remain non-blank`() {
        val multi = BoldMarkdownStripper.strip("  **a** **b**  ")
        assertEquals("  a b  ", multi)
        assertEquals(true, multi.isNotBlank())
    }
}