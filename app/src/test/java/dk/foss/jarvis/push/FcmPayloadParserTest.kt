package dk.foss.jarvis.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FcmPayloadParserTest {
    @Test fun acceptsOnlyNonBlankEventId() {
        assertEquals("123e4567-e89b-12d3-a456-426614174000", FcmPayloadParser.eventId(mapOf("event_id" to " 123e4567-e89b-12d3-a456-426614174000 ")))
        assertNull(FcmPayloadParser.eventId(emptyMap()))
        assertNull(FcmPayloadParser.eventId(mapOf("event_id" to "  ")))
    }

    @Test fun unrelatedAndNotificationKeysAreNotConsumed() {
        assertNull(FcmPayloadParser.eventId(mapOf("title" to "private", "body" to "private")))
        assertEquals("123e4567-e89b-12d3-a456-426614174001", FcmPayloadParser.eventId(mapOf("event_id" to "123e4567-e89b-12d3-a456-426614174001", "notification" to "ignored")))
        assertNull(FcmPayloadParser.eventId(mapOf("event_id" to "123e4567e89b12d3a456426614174002")))
    }
}
