package dk.foss.jarvis.push

import dk.foss.jarvis.hermes.FetchFailureKind
import dk.foss.jarvis.push.RpcErrorClass.*
import org.junit.Assert.assertEquals
import org.junit.Test

class RpcRetryPolicyTest {

    @Test fun `device_not_found REENROLL`() =
        assertEquals(REENROLL, RpcRetryPolicy.classify(null, null, "device_not_found"))

    @Test fun `device_revoked REENROLL`() =
        assertEquals(REENROLL, RpcRetryPolicy.classify(null, null, "device_revoked"))

    @Test fun `device_auth_failed REENROLL`() =
        assertEquals(REENROLL, RpcRetryPolicy.classify(null, null, "device_auth_failed"))

    @Test fun `unsupported_protocol PERMANENT`() =
        assertEquals(PERMANENT, RpcRetryPolicy.classify(null, null, "unsupported_protocol"))

    @Test fun `unknown_operation PERMANENT`() =
        assertEquals(PERMANENT, RpcRetryPolicy.classify(null, null, "unknown_operation"))

    @Test fun `invalid_request PERMANENT`() =
        assertEquals(PERMANENT, RpcRetryPolicy.classify(null, null, "invalid_request"))

    @Test fun `invalid_push PERMANENT`() =
        assertEquals(PERMANENT, RpcRetryPolicy.classify(null, null, "invalid_push"))

    @Test fun `payload_too_large PERMANENT`() =
        assertEquals(PERMANENT, RpcRetryPolicy.classify(null, null, "payload_too_large"))

    @Test fun `event_not_found PERMANENT`() =
        assertEquals(PERMANENT, RpcRetryPolicy.classify(null, null, "event_not_found"))

    @Test fun `invalid_response PERMANENT`() =
        assertEquals(PERMANENT, RpcRetryPolicy.classify(null, null, "invalid_response"))

    @Test fun `codigo desconocido PERMANENT`() =
        assertEquals(PERMANENT, RpcRetryPolicy.classify(null, null, "weird_code"))

    @Test fun `rpcCode null + 401 PERMANENT`() =
        assertEquals(PERMANENT, RpcRetryPolicy.classify(null, 401, null))

    @Test fun `500 RETRY`() =
        assertEquals(RETRY, RpcRetryPolicy.classify(null, 500, null))

    @Test fun `503 RETRY`() =
        assertEquals(RETRY, RpcRetryPolicy.classify(null, 503, null))

    @Test fun `NETWORK RETRY`() =
        assertEquals(RETRY, RpcRetryPolicy.classify(FetchFailureKind.NETWORK, null, null))

    @Test fun `SERIALIZATION RETRY`() =
        assertEquals(RETRY, RpcRetryPolicy.classify(FetchFailureKind.SERIALIZATION, null, null))

    @Test fun `OTHER PERMANENT`() =
        assertEquals(PERMANENT, RpcRetryPolicy.classify(FetchFailureKind.OTHER, null, null))

    @Test fun `HTTP 404 RETRY`() =
        assertEquals(RETRY, RpcRetryPolicy.classify(FetchFailureKind.HTTP, 404, null))
}