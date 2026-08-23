package dk.foss.jarvis.events

import dk.foss.jarvis.hermes.HermesEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLogicTest {
    @Test fun `mapper makes display envelope`() {
        val envelope = EventMapper.toEnvelope(HermesEvent("e1", "alert", 1.0, 2.0, title = "Hi", body = "There", priority = 3, session_id = "s"))
        assertEquals(HermesEventEnvelope("e1", "Hi", "There", "s", 3, 2.0), envelope)
    }

    @Test fun `deduper suppresses repeats and forget allows again`() {
        val deduper = NotificationDeduper()
        assertFalse(deduper.observe("e1"))
        assertTrue(deduper.observe("e1"))
        deduper.forget("e1")
        assertFalse(deduper.observe("e1"))
    }

    @Test fun `stable id is deterministic and distinguishes normal inputs`() {
        assertEquals(StableNotificationId.forEvent("e1"), StableNotificationId.forEvent("e1"))
        assertNotEquals(StableNotificationId.forEvent("e1"), StableNotificationId.forEvent("e2"))
    }

    @Test fun `retry policy permits only bounded failed pending reconnects`() {
        assertTrue(RetryPolicy.shouldRetryOnReconnect(true, true, 0))
        assertTrue(RetryPolicy.shouldRetryOnReconnect(true, true, 1))
        assertFalse(RetryPolicy.shouldRetryOnReconnect(true, true, 2))
        assertFalse(RetryPolicy.shouldRetryOnReconnect(false, true, 0))
        assertFalse(RetryPolicy.shouldRetryOnReconnect(true, false, 0))
    }
}
