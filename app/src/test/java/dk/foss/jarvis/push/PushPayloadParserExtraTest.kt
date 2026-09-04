package dk.foss.jarvis.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushPayloadParserExtraTest {
    @Test
    fun `non object JSON is rejected without throwing`() {
        assertNull(PushPayloadParser.eventIdFrom("[]".toByteArray()))
        assertNull(PushPayloadParser.eventIdFrom("null".toByteArray()))
    }

    @Test
    fun `event id must be non blank`() {
        assertNull(PushPayloadParser.eventIdFrom("{\"event_id\":\"\"}".toByteArray()))
        assertEquals("123e4567-e89b-12d3-a456-426614174000", PushPayloadParser.eventIdFrom("{\"event_id\":\"123e4567-e89b-12d3-a456-426614174000\"}".toByteArray()))
    }

    @Test
    fun `random binary is rejected`() {
        assertNull(PushPayloadParser.eventIdFrom(byteArrayOf(0x7f, 0x00, 0x12, 0x55)))
    }
}
