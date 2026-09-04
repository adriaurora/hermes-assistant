package dk.foss.jarvis.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushPayloadParserTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"
    @Test fun validPayload() = assertEquals(id, PushPayloadParser.eventIdFrom("{\"event_id\":\"$id\"}".toByteArray()))
    @Test fun compactUuidPayloadIsValid() = assertEquals(
        "123e4567e89b12d3a456426614174000",
        PushPayloadParser.eventIdFrom("{\"event_id\":\"123e4567e89b12d3a456426614174000\"}".toByteArray()),
    )
    @Test fun missingPayload() = assertNull(PushPayloadParser.eventIdFrom("{\"kind\":\"x\"}".toByteArray()))
    @Test fun malformedPayloadDoesNotThrow() {
        assertNull(PushPayloadParser.eventIdFrom(byteArrayOf(0, 1, 2)))
        assertNull(PushPayloadParser.eventIdFrom(ByteArray(0)))
    }
    @Test fun malformedNonUuidIsRejected() = assertNull(PushPayloadParser.eventIdFrom("{\"event_id\":\"evt-2\"}".toByteArray()))
    @Test fun malformedCompactUuidIsRejected() {
        assertNull(PushPayloadParser.eventIdFrom("{\"event_id\":\"123e4567e89b12d3a45642661417\"}".toByteArray()))
        assertNull(PushPayloadParser.eventIdFrom("{\"event_id\":\"123E4567E89B12D3A456426614174000\"}".toByteArray()))
    }
    @Test fun additionalKeysAreAllowed() = assertEquals(id, PushPayloadParser.eventIdFrom("{\"x\":1,\"event_id\":\"$id\"}".toByteArray()))
}
