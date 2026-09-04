package dk.foss.jarvis.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

class EventPriorityDecodeTest {
    @Test fun serverStringPrioritiesDecode() {
        assertEquals(0, decode("low"))
        assertEquals(1, decode("normal"))
        assertEquals(2, decode("high"))
    }

    @Test fun numericPriorityRemainsCompatible() {
        assertEquals(7, HermesJson.decodeFromString<HermesEvent>(event("7", false)).priority)
    }

    private fun decode(priority: String) =
        HermesJson.decodeFromString<HermesEvent>(event(priority, true)).priority

    private fun event(priority: String, quoted: Boolean) =
        """{"event_id":"e","event_type":"reminder","created_at":1,"available_at":1,"priority":${if (quoted) "\"$priority\"" else priority}}"""
}
