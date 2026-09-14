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
     * Validation steps (all must pass before canonicalisation):
     * 1. Non-empty trimmed string
     * 2. Parsed by URI.create (throws → blocked)
     * 3. Scheme present and one of {http, https}
     * 4. Absolute (not opaque — e.g. not "http:foo")
     * 5. Has authority with a host
     * 6. No userinfo (credentials belong in the Authorization header)
     * 7. No query string (origins never include query)
     * 8. No fragment (origins never include fragments)
     * 9. For HTTP only: approved in [ApprovedOriginsStore]
     *
     * Canonicalisation (originIdentity) happens **only after** all validation
     * above has passed, so a malformed URL is never silently converted.
     *
     * @throws BlockedRequest when the URL violates policy.
     * @return the canonical [originIdentity] string (scheme + host + port +
     *   path) so callers can log or cache the validated origin.
     */
    fun validate(baseUrl: String): String {
        val trimmed = baseUrl.trim()

        // 1. Empty/blank strings are malformed
        if (trimmed.isEmpty()) throw BlockedRequest("Empty URL")

        // 2. Parse the URL — malformed URLs throw and are blocked
        val uri = runCatching { java.net.URI.create(trimmed) }.getOrNull()
            ?: throw BlockedRequest("Malformed URL: ${baseUrl.take(80)}")

        // 3. URI must have a scheme — bare paths like "//host/path" are not valid endpoints
        val scheme = uri.scheme ?: throw BlockedRequest("URL missing scheme: ${baseUrl.take(80)}")

        // 4. Only http(s) allowed
        val lowerScheme = scheme.lowercase()
        when (lowerScheme) {
            "https" -> {} // fall through to common validation
            "http"  -> {} // fall through to common validation + approval
            else    -> throw BlockedRequest("Unsupported scheme '$lowerScheme' (allowed: http, https)")
        }

        // ── Common validation for BOTH http and https ─────────────────

        // 5. Must NOT be opaque — opaque URIs like "https:foo" have no authority
        if (uri.isOpaque) throw BlockedRequest("Malformed URL (opaque, no authority): ${baseUrl.take(80)}")

        // 6. Must have a host — authority-based URIs always have host
        if (uri.host == null) throw BlockedRequest("URL missing host: ${baseUrl.take(80)}")

        // 7. Must NOT contain userinfo — credentials belong in Authorization header
        if (uri.userInfo != null) throw BlockedRequest("URL contains userinfo: ${baseUrl.take(80)}")

        // 8. Must NOT contain query — origins don't include query strings
        if (uri.query != null) throw BlockedRequest("URL contains query: ${baseUrl.take(80)}")

        // 9. Must NOT contain fragment — origins don't include fragments
        if (uri.fragment != null) throw BlockedRequest("URL contains fragment: ${baseUrl.take(80)}")

        // ── Scheme-specific checks ───────────────────────────────────

        // 10. HTTP — must be approved (per endpoint, not per key).
        if (lowerScheme == "http") {
            if (!approvedOrigins.isApprovedSync(originIdentity(baseUrl))) {
                throw BlockedRequest(
                    "Insecure HTTP to '$baseUrl' is blocked. " +
                        "This app enforces HTTPS by default. " +
                        "If you control the server, use HTTPS. " +
                        "For a development LAN server, enable the 'Allow insecure HTTP' option in Settings."
                )
            }
        }

        // All checks passed — canonicalise
        return originIdentity(baseUrl)
    }
}

/** Thrown when [NetworkGate] decides that a request must not be sent. */
class BlockedRequest(val reason: String) : RuntimeException(reason)