package dk.foss.jarvis.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventWireTest {
    @Test
    fun `event page and registration responses decode`() {
        val event = HermesJson.decodeFromString(HermesEvent.serializer(), """
            {"event_id":"evt-1","event_type":"reminder","created_at":1787382860.47,
             "available_at":1787382900.25,"expires_at":1787386500.0,"source":"cron",
             "source_id":"job-7","session_id":"session-1","title":"Meeting",
             "body":"Stand-up in five minutes","priority":2,"status":"pending","device_id":"dev-1",
             "ignored":"ok"}
        """.trimIndent())
        assertEquals("evt-1", event.event_id)
        assertEquals(1787382900.25, event.available_at, 0.0)
        assertEquals(2, event.priority)
        assertEquals("session-1", event.session_id)
        assertNull(HermesJson.decodeFromString(HermesEvent.serializer(),
            """{"event_id":"e","event_type":"x","created_at":1.0,"available_at":2.0}""").title)

        val page = HermesJson.decodeFromString(HermesEventsPage.serializer(), """{"events":[${HermesJson.encodeToString(HermesEvent.serializer(), event)}]}""")
        assertEquals(1, page.events.size)
        val registered = HermesJson.decodeFromString(DeviceRegisterResponse.serializer(), """{"device_id":"dev-1","status":"registered"}""")
        assertEquals("dev-1", registered.device_id)
        assertEquals("ok", HermesJson.decodeFromString(DeviceOpsResponse.serializer(), """{"status":"ok"}""").status)
    }
}
