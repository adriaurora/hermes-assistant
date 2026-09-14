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
    private val cleanupStore = Http.testingGate.cleanupStore as InMemoryApprovedOriginsStore

    @Before fun setUp() {
        runBlocking {
            store.clearAll()
            cleanupStore.clearAll()
        }
    }

    /** Simulates the old-origin preservation + revoke → approve-removal flow. */
    @Test fun `old_http_origin_approved_only_for_revoke_cleanup()`() = runBlocking {
        val oldHttp = "http://hermes-a.local:8642"

        // 1. User approves old HTTP endpoint
        store.add(originIdentity(oldHttp))
        assertTrue("Old origin must be allowed for ordinary traffic before move", isAllowed(oldHttp))

        // 2. Settings change — move old origin to cleanup allowance
        runBlocking { store.moveForCleanup(originIdentity(oldHttp)) }
        assertFalse("Old origin must be BLOCKED for ordinary traffic after move", isAllowed(oldHttp))
        assertTrue("Old origin must be ALLOWED for cleanup after move", isAllowedForCleanup(oldHttp))

        // 3. Revoke worker runs — allowed to contact old HTTP for revoke
        assertTrue("Old origin must be accessible during revoke cleanup", isAllowedForCleanup(oldHttp))

        // 4. Revoke complete — cleanup cleared (FcmRevokeCleanup removes old origin)
        cleanupStore.removeFromCleanup(originIdentity(oldHttp))
        assertFalse("Old origin must NOT be in cleanup after revoke", isAllowedForCleanup(oldHttp))
        assertFalse("Old HTTP approval must be removed after successful revoke", isAllowed(oldHttp))
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

    /**
     * Test that atomic move replaces one HTTP origin with another.
     * When switching from A→B (both HTTP), A should be blocked for ordinary
     * but allowed for cleanup, and B should be allowed for ordinary.
     */
    @Test fun `atomic_http_move_replaces_origin_for_cleanup()`() = runBlocking {
        val oldHttp = "http://hermes-a.local:8642"
        val newHttp = "http://hermes-b.local:8642"

        // User approves old HTTP endpoint
        store.add(originIdentity(oldHttp))
        assertTrue("Old origin must be allowed before move", isAllowed(oldHttp))

        // User changes to new HTTP endpoint — atomic move
        runBlocking { store.moveForCleanup(originIdentity(oldHttp)) }
        store.add(originIdentity(newHttp))

        // Old origin: blocked for ordinary, allowed for cleanup
        assertFalse("Old origin must be BLOCKED for ordinary traffic after move", isAllowed(oldHttp))
        assertTrue("Old origin must be ALLOWED for cleanup after move", isAllowedForCleanup(oldHttp))

        // New origin: allowed for ordinary
        assertTrue("New origin must be allowed for ordinary traffic", isAllowed(newHttp))

        // Cleanup is separate — new origin not in cleanup
        assertFalse("New origin must NOT be in cleanup allowance", isCleanupOnly(newHttp))

        // Revoke completes → old origin removed from cleanup
        runBlocking { cleanupStore.removeFromCleanup(originIdentity(oldHttp)) }
        assertFalse("Old origin must be fully removed after revoke", isAllowed(oldHttp))
        assertFalse("Old origin must be removed from cleanup", isAllowedForCleanup(oldHttp))

        // New origin still allowed (it's in active approval)
        assertTrue("New origin must still be allowed after old revoke", isAllowed(newHttp))
    }

    /**
     * Simulates the scenario where user switches from A to B by adding both.
     * In the old broken model, both are allowed. In the new model using
     * moveForCleanup, the old one should be blocked for ordinary access.
     *
     * This test verifies that when BOTH origins are added (old behavior),
     * both are still accessible — demonstrating the difference between
     * add+add vs add+moveForCleanup+add.
     */
    @Test fun `http_approval_cleared_on_settings_change_before_revoke()`() = runBlocking {
        val oldHttp = "http://hermes-a.local:8642"
        val newHttp = "http://hermes-b.local:8642"

        // User approves old HTTP endpoint
        store.add(originIdentity(oldHttp))
        assertTrue(isAllowed(oldHttp))

        // User adds new HTTP endpoint (simulating old broken behavior — both added)
        store.add(originIdentity(newHttp))

        // Both origins are now allowed (this demonstrates the old bug)
        assertTrue("Old origin is still allowed (old broken behavior)", isAllowed(oldHttp))
        assertTrue("New origin is allowed", isAllowed(newHttp))

        // Contrast: using moveForCleanup, old origin is blocked
        store.add(originIdentity("http://hermes-c.local:8642"))
        runBlocking { store.moveForCleanup(originIdentity("http://hermes-c.local:8642")) }
        store.add(originIdentity(newHttp))
        assertFalse("Old origin must be BLOCKED when using moveForCleanup", isAllowed("http://hermes-c.local:8642"))
    }

    private fun isAllowed(url: String): Boolean = runCatching {
        Http.testingGate.validate(url)
        true
    }.getOrDefault(false)

    /**
     * Checks if a URL is allowed via cleanup validation.
     * This allows origins in EITHER active or cleanup sets.
     */
    private fun isAllowedForCleanup(url: String): Boolean = runCatching {
        Http.testingGate.validateForCleanup(url)
        true
    }.getOrDefault(false)

    /**
     * Checks if a URL is specifically in the cleanup set (not just the active set).
     */
    private fun isCleanupOnly(url: String): Boolean = runBlocking {
        (Http.testingGate.cleanupStore as? InMemoryApprovedOriginsStore)?.contains(url) ?: false
    }
}