package dk.foss.jarvis.push

import dk.foss.jarvis.hermes.EventFetchException
import dk.foss.jarvis.hermes.FetchFailureKind
import dk.foss.jarvis.hermes.HermesHttpException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for FcmRevokePolicy.classify() covering:
 * - null error  RevokeSuccess
 * - HermesHttpException status codes (404, 401, 403, 429, 500)
 * - EventFetchException with rpcCode (device_not_found, device_revoked, device_auth_failed)
 * - EventFetchException with raw statusCode (404, 401, 403, 429, 500)
 * - EventFetchException with network/serialization/other kind
 * - Integration: real EventRpcClient.revoke() + MockWebServer  exceptionOrNull  classify
 */
class FcmRevokePolicyTest {

    // ── null / no error ────────────────────────────────────────────────

    @Test fun `null error is success`() {
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.classify(null))
    }

    // ── HermesHttpException (raw HTTP, no RPC envelope) ─────────────────

    @Test fun `HermesHttpException 404 is success`() {
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.classify(HermesHttpException(404)))
    }

    @Test fun `HermesHttpException 401 is credential rejected`() {
        assertEquals(FcmRevokePolicy.RevokeOutcome.CredentialRejected, FcmRevokePolicy.classify(HermesHttpException(401)))
    }

    @Test fun `HermesHttpException 403 is credential rejected`() {
        assertEquals(FcmRevokePolicy.RevokeOutcome.CredentialRejected, FcmRevokePolicy.classify(HermesHttpException(403)))
    }

    @Test fun `HermesHttpException 429 is retry`() {
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(HermesHttpException(429)))
    }

    @Test fun `HermesHttpException 500 is retry`() {
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(HermesHttpException(500)))
    }

    @Test fun `HermesHttpException 503 is retry`() {
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(HermesHttpException(503)))
    }

    // ── EventFetchException with rpcCode ───────────────────────────────

    @Test fun `EventFetchException device_not_found rpcCode is success`() {
        val e = EventFetchException(FetchFailureKind.HTTP, rpcCode = "device_not_found")
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.classify(e))
    }

    @Test fun `EventFetchException device_revoked rpcCode is success`() {
        val e = EventFetchException(FetchFailureKind.HTTP, rpcCode = "device_revoked")
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.classify(e))
    }

    @Test fun `EventFetchException device_auth_failed rpcCode is credential rejected`() {
        val e = EventFetchException(FetchFailureKind.HTTP, rpcCode = "device_auth_failed")
        assertEquals(FcmRevokePolicy.RevokeOutcome.CredentialRejected, FcmRevokePolicy.classify(e))
    }

    @Test fun `EventFetchException unknown rpcCode falls through to statusCode then retry`() {
        val e = EventFetchException(FetchFailureKind.HTTP, rpcCode = "some_unknown_code", statusCode = 500)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(e))
    }

    // ── EventFetchException with raw statusCode (no rpcCode) ───────────

    @Test fun `EventFetchException 404 status is success`() {
        val e = EventFetchException(FetchFailureKind.HTTP, statusCode = 404)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.classify(e))
    }

    @Test fun `EventFetchException 401 status is credential rejected`() {
        val e = EventFetchException(FetchFailureKind.HTTP, statusCode = 401)
        assertEquals(FcmRevokePolicy.RevokeOutcome.CredentialRejected, FcmRevokePolicy.classify(e))
    }

    @Test fun `EventFetchException 403 status is credential rejected`() {
        val e = EventFetchException(FetchFailureKind.HTTP, statusCode = 403)
        assertEquals(FcmRevokePolicy.RevokeOutcome.CredentialRejected, FcmRevokePolicy.classify(e))
    }

    @Test fun `EventFetchException 429 status is retry`() {
        val e = EventFetchException(FetchFailureKind.HTTP, statusCode = 429)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(e))
    }

    @Test fun `EventFetchException 500 status is retry`() {
        val e = EventFetchException(FetchFailureKind.HTTP, statusCode = 500)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(e))
    }

    @Test fun `EventFetchException network kind is retry`() {
        val e = EventFetchException(FetchFailureKind.NETWORK, cause = java.net.ConnectException("timeout"))
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(e))
    }

    @Test fun `EventFetchException serialization kind is retry`() {
        val e = EventFetchException(FetchFailureKind.SERIALIZATION)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(e))
    }

    @Test fun `EventFetchException other kind is retry`() {
        val e = EventFetchException(FetchFailureKind.OTHER)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(e))
    }

    // ── EventFetchException: rpcCode takes priority over statusCode ────

    @Test fun `rpcCode device_auth_failed overrides 404 statusCode`() {
        // Even if statusCode is 404, rpcCode takes priority
        val e = EventFetchException(FetchFailureKind.HTTP, statusCode = 404, rpcCode = "device_auth_failed")
        assertEquals(FcmRevokePolicy.RevokeOutcome.CredentialRejected, FcmRevokePolicy.classify(e))
    }

    @Test fun `rpcCode device_not_found overrides 403 statusCode`() {
        val e = EventFetchException(FetchFailureKind.HTTP, statusCode = 403, rpcCode = "device_not_found")
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.classify(e))
    }

    // ── Unrelated throwables ───────────────────────────────────────────

    @Test fun `generic exception is retry`() {
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(Exception("boom")))
    }

    @Test fun `typed 404 message but not HermesHttpException is retry`() {
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(Exception("HTTP 404: not found")))
    }

    // ── Integration: real EventRpcClient.revoke() + MockWebServer ──────

    private val server = MockWebServer()

    @Before fun setUp() { server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun client(apiKey: String = "KEY", deviceId: String = "d-1", deviceSecret: String = "s-1") =
        dk.foss.jarvis.hermes.EventRpcClient(server.url("/").toString().trimEnd('/'), apiKey, deviceId, deviceSecret)

    @Test fun `integration revoke 200 success classify null is RevokeSuccess`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"ok":true,"protocol_version":1,"result":{"device_id":"d-1","state":"revoked"}}"""))
        val result = client().revoke()
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.classify(result.exceptionOrNull()))
    }

    @Test fun `integration revoke 404 classify EventFetchException is RevokeSuccess`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = client().revoke()
        val ex = result.exceptionOrNull()
        assert(ex is EventFetchException) { "expected EventFetchException, got ${ex?.javaClass}" }
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.classify(ex))
    }

    @Test fun `integration revoke RPC device_not_found is RevokeSuccess`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"ok":false,"error":{"code":"device_not_found","http_status":404}}"""))
        val result = client().revoke()
        val ex = result.exceptionOrNull()
        assert(ex is EventFetchException)
        assertEquals(FetchFailureKind.HTTP, (ex as EventFetchException).kind)
        assertEquals("device_not_found", ex.rpcCode)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.classify(ex))
    }

    @Test fun `integration revoke RPC device_revoked  RevokeSuccess`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"ok":false,"error":{"code":"device_revoked","http_status":410}}"""))
        val result = client().revoke()
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals("device_revoked", ex.rpcCode)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RevokeSuccess, FcmRevokePolicy.classify(ex))
    }

    @Test fun `integration revoke RPC device_auth_failed  CredentialRejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"ok":false,"error":{"code":"device_auth_failed","message":"Invalid credentials","http_status":403}}"""))
        val result = client().revoke()
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals("device_auth_failed", ex.rpcCode)
        assertEquals(403, ex.statusCode)
        assertEquals(FcmRevokePolicy.RevokeOutcome.CredentialRejected, FcmRevokePolicy.classify(ex))
    }

    @Test fun `integration revoke raw 401  CredentialRejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client().revoke()
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals(FetchFailureKind.HTTP, ex.kind)
        assertEquals(401, ex.statusCode)
        assertNull(ex.rpcCode)
        assertEquals(FcmRevokePolicy.RevokeOutcome.CredentialRejected, FcmRevokePolicy.classify(ex))
    }

    @Test fun `integration revoke raw 403  CredentialRejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = client().revoke()
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals(FetchFailureKind.HTTP, ex.kind)
        assertEquals(403, ex.statusCode)
        assertEquals(FcmRevokePolicy.RevokeOutcome.CredentialRejected, FcmRevokePolicy.classify(ex))
    }

    @Test fun `integration revoke 429  RetryAgain`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        val result = client().revoke()
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals(FetchFailureKind.HTTP, ex.kind)
        assertEquals(429, ex.statusCode)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(ex))
    }

    @Test fun `integration revoke 500  RetryAgain`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = client().revoke()
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals(FetchFailureKind.HTTP, ex.kind)
        assertEquals(500, ex.statusCode)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(ex))
    }

    @Test fun `integration revoke 503  RetryAgain`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = client().revoke()
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals(FetchFailureKind.HTTP, ex.kind)
        assertEquals(503, ex.statusCode)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(ex))
    }

    @Test fun `integration revoke network failure  RetryAgain`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = client().revoke()
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals(FetchFailureKind.NETWORK, ex.kind)
        assertEquals(FcmRevokePolicy.RevokeOutcome.RetryAgain, FcmRevokePolicy.classify(ex))
    }
}
