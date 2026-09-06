package dk.foss.jarvis.push

import dk.foss.jarvis.hermes.EventFetchException
import dk.foss.jarvis.hermes.FetchFailureKind
import dk.foss.jarvis.hermes.HermesHttpException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for StaleDeviceFallback.isConfirmedNotFound.
 *
 * Only a genuine HTTP 404 (v1 or v2) should trigger the legacy stale-device
 * fallback; rpcCode-based errors (device_not_found) are v1 domain and must
 * NOT activate this legacy path.
 */
class StaleDeviceFallbackTest {

    @Test fun testHermesHttpException404IsConfirmedNotFound() {
        assertTrue(StaleDeviceFallback.isConfirmedNotFound(HermesHttpException(404)))
    }

    @Test fun testIllegalStateException404IsConfirmedNotFound() {
        assertTrue(StaleDeviceFallback.isConfirmedNotFound(IllegalStateException("HTTP 404: not found")))
    }

    @Test fun testHermesHttpException500IsNotConfirmedNotFound() {
        assertFalse(StaleDeviceFallback.isConfirmedNotFound(HermesHttpException(500)))
    }

    @Test fun testIllegalStateException409IsNotConfirmedNotFound() {
        assertFalse(StaleDeviceFallback.isConfirmedNotFound(IllegalStateException("HTTP 409: conflict")))
    }

    @Test fun testEventFetchException404WithRpcCodeIsNotConfirmedNotFound() {
        val e = EventFetchException(FetchFailureKind.HTTP, 404, rpcCode = "event_not_found")
        assertFalse(StaleDeviceFallback.isConfirmedNotFound(e))
    }

    @Test fun testNullIsNotConfirmedNotFound() {
        assertFalse(StaleDeviceFallback.isConfirmedNotFound(null))
    }
}