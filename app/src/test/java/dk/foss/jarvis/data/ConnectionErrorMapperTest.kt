package dk.foss.jarvis.data

import dk.foss.jarvis.hermes.HermesHttpError
import dk.foss.jarvis.net.BlockedRequest
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [ConnectionErrorMapper] — semantic error messages with
 * no implementation details exposed.
 *
 * Contracts:
 * - No raw stack traces, IP addresses, or HTTP status codes
 * - Auth errors get a specific "API key" message
 * - Network errors get a generic connectivity message
 * - Gate errors get a neutral "blocked" message
 * - Settings validation errors get a "URL format" message
 */
class ConnectionErrorMapperTest {

    @Test fun `hermesAuth_401_gives_api_key_message`() {
        val err = HermesHttpError(401, null, null, "401 Unauthorized")
        val msg = ConnectionErrorMapper.mapTestConnectionError(err)
        assertTrue("Must mention API key", msg.contains("API key", ignoreCase = true))
        assertFalse("No status code leaked", msg.contains("401"))
        assertFalse("No raw exception", msg.contains("Unauthorized"))
    }

    @Test fun `hermesAuth_rpcsCode_gives_api_key_message`() {
        val err = HermesHttpError(null, "gateway_auth_failed", null, "auth failed")
        val msg = ConnectionErrorMapper.mapTestConnectionError(err)
        assertTrue("Must mention API key", msg.contains("API key", ignoreCase = true))
        assertFalse("No RPC code leaked", msg.contains("gateway_auth"))
    }

    @Test fun `hermesAuth_403_gives_api_key_message`() {
        val err = HermesHttpError(403, null, null, "Forbidden")
        val msg = ConnectionErrorMapper.mapTestConnectionError(err)
        assertTrue("Must mention API key", msg.contains("API key", ignoreCase = true))
        assertFalse("No code leaked", msg.contains("403"))
    }

    @Test fun `hermesHttp_404_gives_connect_message`() {
        val err = HermesHttpError(404, "session_not_found", null, "session not found")
        val msg = ConnectionErrorMapper.mapTestConnectionError(err)
        assertTrue("Must mention URL/connect", msg.contains("URL") || msg.contains("conectar"))
        assertFalse("No status code", msg.contains("404"))
    }

    @Test fun `generic_http_error_gets_connect_message`() {
        val err = HermesHttpError(500, null, null, "Internal Server Error")
        val msg = ConnectionErrorMapper.mapTestConnectionError(err)
        assertTrue("Generic connect message", msg.contains("conectar"))
        assertFalse("No status code", msg.contains("500"))
        assertFalse("No tech details", msg.contains("Server"))
    }

    @Test fun `blockedRequest_gets_neutral_message`() {
        val err = BlockedRequest("Insecure HTTP to 'http://10.0.0.1' is blocked")
        val msg = ConnectionErrorMapper.mapTestConnectionError(err)
        assertEquals("Neutral blocked message", "✕ Conexión no permitida.", msg)
        // No IP leaked
        assertFalse(msg.contains("10.0.0.1"))
    }

    @Test fun `invalidConnectionSettings_gets_url_format_message`() {
        val err = InvalidConnectionSettings("Empty URL")
        val msg = ConnectionErrorMapper.mapTestConnectionError(err)
        assertTrue("URL format message", msg.contains("URL") || msg.contains("válida"))
        assertFalse("No 'Empty' leaked", msg.contains("Empty"))
        assertFalse("No raw exception", msg.contains("Empty URL"))
    }

    @Test fun `httpDowngrade_gives_maintain_message`() {
        val err = HttpDowngradeNotAllowed()
        val msg = ConnectionErrorMapper.mapTestConnectionError(err)
        assertTrue("HTTPS to HTTP message", msg.contains("HTTPS") || msg.contains("HTTP"))
        assertTrue("Maintain config", msg.contains("configuración anterior") || msg.contains("configuración"))
        // No internal class name leaked
        assertFalse(msg.contains("HttpDowngradeNotAllowed"))
    }

    @Test fun `unknown_exception_gets_generic_connect_message`() {
        val err = RuntimeException("java.net.SocketException: Connection refused")
        val msg = ConnectionErrorMapper.mapTestConnectionError(err)
        assertTrue("Generic connect message", msg.contains("conectar"))
        // No stack trace leaked
        assertFalse(msg.contains("SocketException"))
        assertFalse(msg.contains("Connection refused"))
        assertFalse(msg.contains("java.net"))
    }

    // ── Save error mapping ──────────────────────────────────────────

    @Test fun `saveHttpDowngrade_gives_maintain_message`() {
        val msg = ConnectionErrorMapper.mapSaveError(HttpDowngradeNotAllowed())
        assertTrue("HTTPS to HTTP", msg.contains("HTTPS") || msg.contains("HTTP"))
        assertTrue("Maintain config", msg.contains("configuración anterior") || msg.contains("configuración"))
        assertFalse(msg.contains("HttpDowngradeNotAllowed"))
    }

    @Test fun `saveInvalidSettings_gives_url_format_message`() {
        val msg = ConnectionErrorMapper.mapSaveError(InvalidConnectionSettings("Opaque URL"))
        assertTrue("URL format", msg.contains("URL") || msg.contains("válida"))
        assertFalse(msg.contains("Opaque"))
    }

    @Test fun `saveBlockedRequest_gives_neutral_message`() {
        val msg = ConnectionErrorMapper.mapSaveError(BlockedRequest("Malformed URL"))
        assertEquals("Neutral blocked message", "✕ Conexión no permitida.", msg)
    }

    @Test fun `saveGeneric_throws_safe_message`() {
        val msg = ConnectionErrorMapper.mapSaveError(RuntimeException("DataStore locked"))
        assertTrue("Generic save message", msg.contains("configuración") || msg.contains("guardar"))
        assertFalse(msg.contains("DataStore"))
        assertFalse(msg.contains("locked"))
    }
}