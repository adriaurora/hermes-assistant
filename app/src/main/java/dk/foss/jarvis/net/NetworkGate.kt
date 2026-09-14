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
 * 2. **Non-http(s) / malformed → blocked** — never reaches the network.
 * 3. **HTTP only for approved endpoints** — when [ApprovedOriginsStore] contains
 *    the normalised origin (scheme + lowercase host + explicit/default port).
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

        // 1. Check scheme — only http(s) allowed.
        when (val scheme = uri.scheme?.lowercase()) {
            "https" -> return originIdentity(baseUrl)
            "http" -> { /* HTTP — check approval */ }
            else -> throw BlockedRequest("Unsupported scheme '$scheme' (allowed: http, https)")
        }

        // 2. HTTP — must be approved (per endpoint, not per key).
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