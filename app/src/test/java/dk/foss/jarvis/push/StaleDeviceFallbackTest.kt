package dk.foss.jarvis.push

import dk.foss.jarvis.hermes.EventFetchException
import dk.foss.jarvis.hermes.FetchFailureKind
import dk.foss.jarvis.hermes.HermesHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for StaleDeviceFallback.isConfirmedNotFound and isAuthRejection.
 *
 * Only a genuine HTTP 404 (v1 or v2) should trigger the legacy stale-device
 * fallback; rpcCode-based errors (device_not_found) are v1 domain and must
 * NOT activate this legacy path.
 *
 * Auth-rejection (401/403) is terminal per M11: credenciales definitivamente
 * rechazadas → no reintentar.
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

    // --- isAuthRejection tests (M11) ---

    @Test fun isAuthRejection_hermesHttp401() {
        assertTrue(StaleDeviceFallback.isAuthRejection(HermesHttpException(401)))
    }

    @Test fun isAuthRejection_hermesHttp403() {
        assertTrue(StaleDeviceFallback.isAuthRejection(HermesHttpException(403)))
    }

    @Test fun isAuthRejection_hermesHttp404() {
        assertFalse(StaleDeviceFallback.isAuthRejection(HermesHttpException(404)))
    }

    @Test fun isAuthRejection_hermesHttp500() {
        assertFalse(StaleDeviceFallback.isAuthRejection(HermesHttpException(500)))
    }

    @Test fun isAuthRejection_illegalState401() {
        assertTrue(StaleDeviceFallback.isAuthRejection(IllegalStateException("HTTP 401: Unauthorized")))
    }

    @Test fun isAuthRejection_illegalState403() {
        assertTrue(StaleDeviceFallback.isAuthRejection(IllegalStateException("HTTP 403: Forbidden")))
    }

    @Test fun isAuthRejection_illegalState409() {
        assertFalse(StaleDeviceFallback.isAuthRejection(IllegalStateException("HTTP 409: Conflict")))
    }

    @Test fun isAuthRejection_null() {
        assertFalse(StaleDeviceFallback.isAuthRejection(null))
    }
}