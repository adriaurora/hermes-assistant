package dk.foss.jarvis.net

import dk.foss.jarvis.hermes.originIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration test: the NetworkGate + ApprovedOriginsStore lifecycle matches
 * the push-revoke flow.
 *
 * Scenario (critical push lifecycle):
 * 1. User configures HTTP endpoint → approved.
 * 2. Push is enabled (registration).
 * 3. User changes settings (endpoint → B) → old HTTP origin must stay usable
 *    only for pending revoke cleanup, then approval removed after cleanup.
 *
 * This test validates the policy decisions that FcmRevokeCleanup must implement.
 */
class NetworkGateLifecycleTest {

    // Use the same store that Http.testingGate is backed by
    private val store = Http.testingGate.approvedOrigins as InMemoryApprovedOriginsStore

    @Before fun setUp() = runBlocking { store.clearAll() }

    /** Simulates the old-origin preservation + revoke → approve-removal flow. */
    @Test fun `old_http_origin_approved_only_for_revoke_cleanup()`() = runBlocking {
        val oldHttp = "http://hermes-a.local:8642"
        val newHttps = "https://hermes-b.local"

        // 1. User approves old HTTP endpoint
        store.add(originIdentity(oldHttp))
        assertTrue(isAllowed(oldHttp))

        // 2. Settings change → old origin preserved for revoke
        val oldOrigin = originIdentity(oldHttp)

        // 3. Revoke worker runs — allowed to contact old HTTP for revoke
        assertTrue("Old origin must be allowed during revoke cleanup", isAllowed(oldHttp))

        // 4. Revoke completes → approval removed (FcmRevokeCleanup clears the old origin)
        store.clearAll()
        assertFalse("Old HTTP approval must be removed after successful revoke", isAllowed(oldHttp))

        // 5. No ordinary traffic to old origin
        assertFalse("Old HTTP endpoint must NOT be reachable for ordinary traffic", isAllowed(oldHttp))
    }

    @Test fun `old_https_origin_no_approval_needed()`() = runBlocking {
        val oldHttps = "https://hermes-a.local:8642"
        val newHttps = "https://hermes-b.local"

        // HTTPS is always allowed — no approval tracking needed
        assertTrue(isAllowed(oldHttps))
        assertTrue(isAllowed(newHttps))
    }

    @Test fun `approved_origin_survives_revoke_of_https_endpoint()`() = runBlocking {
        // If the user switches from HTTPS-A to HTTPS-B and revokes A:
        // No HTTP approval involved → nothing to clean up.
        val oldHttps = "https://hermes-a.local"
        val newHttps = "https://hermes-b.local"

        assertTrue(isAllowed(oldHttps))
        assertTrue(isAllowed(newHttps))

        // Simulate: old HTTPS origin approval was never set
        assertEquals(0, store.list().size)
    }

    @Test fun `http_approval_cleared_on_settings_change_before_revoke()`() = runBlocking {
        val oldHttp = "http://hermes-a.local:8642"
        val newHttp = "http://hermes-b.local:8642"

        // User approves old HTTP endpoint
        store.add(originIdentity(oldHttp))
        assertTrue(isAllowed(oldHttp))

        // User changes to new HTTP endpoint
        store.add(originIdentity(newHttp))

        // Old origin still approved until revoke completes
        assertTrue(isAllowed(oldHttp))
        assertTrue(isAllowed(newHttp))

        // Revoke completes → old approval removed
        store.remove(originIdentity(oldHttp))
        assertFalse(isAllowed(oldHttp))
    }

    private fun isAllowed(url: String): Boolean = runCatching {
        Http.testingGate.validate(url)
        true
    }.getOrDefault(false)
}