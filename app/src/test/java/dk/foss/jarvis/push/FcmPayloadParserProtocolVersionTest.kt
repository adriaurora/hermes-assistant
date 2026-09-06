package dk.foss.jarvis.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Añade casos de protocol_version al FcmPayloadParserTest existente.
 */
class FcmPayloadParserProtocolVersionTest {

    private val eventId = "123e4567-e89b-12d3-a456-426614174000"

    @Test fun `protocol_version one returns eventId`() {
        val result = FcmPayloadParser.eventId(
            mapOf("event_id" to eventId, "protocol_version" to "1")
        )
        assertEquals(eventId, result)
    }

    @Test fun `protocol_version null returns eventId`() {
        val result = FcmPayloadParser.eventId(
            mapOf("event_id" to eventId)
        )
        assertEquals(eventId, result)
    }

    @Test fun `protocol_version empty string returns eventId`() {
        val result = FcmPayloadParser.eventId(
            mapOf("event_id" to eventId, "protocol_version" to "")
        )
        assertEquals(eventId, result)
    }

    @Test fun `protocol_version two returns null`() {
        val result = FcmPayloadParser.eventId(
            mapOf("event_id" to eventId, "protocol_version" to "2")
        )
        assertNull(result)
    }

    @Test fun `protocol_version one dot zero returns null`() {
        val result = FcmPayloadParser.eventId(
            mapOf("event_id" to eventId, "protocol_version" to "1.0")
        )
        assertNull(result)
    }

    @Test fun `protocol_version space one space returns eventId`() {
        val result = FcmPayloadParser.eventId(
            mapOf("event_id" to eventId, "protocol_version" to " 1 ")
        )
        assertEquals(eventId, result)
    }

    @Test fun `event_id not a UUID with protocol_version one returns null`() {
        val result = FcmPayloadParser.eventId(
            mapOf("event_id" to "not-a-uuid", "protocol_version" to "1")
        )
        assertNull(result)
    }
}