package dk.foss.jarvis.net

import dk.foss.jarvis.hermes.originIdentity

/**
 * Centralised network policy gate — fail-closed by default.
 *
 * All Hermes-originated HTTP traffic (chat, events, FCM push lifecycle including
 * revoke/registration) passes through this gate before any socket is opened.
 *
 * ## Policy
 *
 * 1. **HTTPS always allowed** — TLS verification is preserved.
 * 2. **Malformed → blocked** — missing host, opaque URI, userinfo, query,
 *    fragment → never reaches the network.
 * 3. **HTTP only for approved endpoints** — when [ApprovedOriginsStore]
 *    contains the normalised origin (scheme + lowercase host + explicit/
 *    default port + path, no trailing slash).
 * 4. **No network before block** — every caller MUST invoke this gate *before*
 *    constructing an [okhttp3.Request] or any other transport primitive.
 *
 * Approval is **per endpoint** (origin), not per API key. The identity used is
 * the same normalised origin that [dk.foss.jarvis.hermes.originIdentity] builds
 * for conversation isolation (scheme lowercased, host lowercased, default port
 * folded, path preserved, no trailing slash).
 *
 * This object is stateless; it delegates to [approvedOrigins] for the
 * approved list.  The store is passed at construction so that production code
 * and tests can inject different implementations.
 */
class NetworkGate(val approvedOrigins: ApprovedOriginsStore) {

    /**
     * Validate [baseUrl] before any network call.
     *
     * @throws BlockedRequest when the URL violates policy (HTTPS always
     *   allowed, HTTP only for approved, malformed always blocked).
     * @return the canonical [originIdentity] string (scheme + host + port +
     *   path) so callers can log or cache the validated origin.
     */
    fun validate(baseUrl: String): String {
        val trimmed = baseUrl.trim()

        // Empty/blank strings are malformed
        if (trimmed.isEmpty()) throw BlockedRequest("Empty URL")

        // Parse the URL — malformed URLs throw and are blocked
        val uri = runCatching { java.net.URI.create(trimmed) }.getOrNull()
            ?: throw BlockedRequest("Malformed URL: ${baseUrl.take(80)}")

        // URI must have a scheme — bare paths like "//host/path" are not valid endpoints
        val scheme = uri.scheme ?: throw BlockedRequest("URL missing scheme: ${baseUrl.take(80)}")

        // Only http(s) allowed
        val lowerScheme = scheme.lowercase()
        when (lowerScheme) {
            "https" -> {
                // HTTPS: validate that the URI is well-formed (has authority).
                // Opaque URIs like "https:foo" have no authority → reject.
                if (!uri.isAbsolute) throw BlockedRequest("Malformed HTTPS (not absolute): ${baseUrl.take(80)}")
                if (uri.host == null) throw BlockedRequest("HTTPS missing host: ${baseUrl.take(80)}")
                return originIdentity(baseUrl)
            }
            "http" -> { /* HTTP — check approval and strictness below */ }
            else -> throw BlockedRequest("Unsupported scheme '$lowerScheme' (allowed: http, https)")
        }

        // HTTP — strict checks before approval lookup:
        // 1. Must be absolute (authority-based), not opaque like "http:foo"
        if (!uri.isAbsolute) throw BlockedRequest("Malformed HTTP (not absolute): ${baseUrl.take(80)}")

        // 2. Must have a host — authority-based URIs always have host
        if (uri.host == null) throw BlockedRequest("HTTP missing host: ${baseUrl.take(80)}")

        // 3. Must NOT contain userinfo — credentials belong in Authorization header
        if (uri.userInfo != null) throw BlockedRequest("HTTP contains userinfo: ${baseUrl.take(80)}")

        // 4. Must NOT contain query — origins don't include query strings
        if (uri.query != null) throw BlockedRequest("HTTP contains query: ${baseUrl.take(80)}")

        // 5. Must NOT contain fragment — origins don't include fragments
        if (uri.fragment != null) throw BlockedRequest("HTTP contains fragment: ${baseUrl.take(80)}")

        // 6. HTTP — must be approved (per endpoint, not per key).
        if (!approvedOrigins.isApprovedSync(originIdentity(baseUrl))) {
            throw BlockedRequest(
                "Insecure HTTP to '$baseUrl' is blocked. " +
                    "This app enforces HTTPS by default. " +
                    "If you control the server, use HTTPS. " +
                    "For a development LAN server, enable the 'Allow insecure HTTP' option in Settings."
            )
        }

        return originIdentity(baseUrl)
    }
}

/** Thrown when [NetworkGate] decides that a request must not be sent. */
class BlockedRequest(val reason: String) : RuntimeException(reason)