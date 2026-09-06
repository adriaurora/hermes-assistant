package dk.foss.jarvis.push

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exhaustive table test for LifecycleGuards predicates.
 *
 * Enabled = true AND no revoke AND no clear -> true; every other combination
 * -> false.
 */
class LifecycleGuardsTest {

    private val table = arrayOf(
        // enabled, pendingRevoke, pendingClear, expected
        arrayOf(true,  false,       false,       true),   // only valid combo
        arrayOf(false, false,       false,       false),  // disabled
        arrayOf(true,  true,        false,       false),  // revoke pending
        arrayOf(true,  false,       true,        false),  // credential clear pending
        arrayOf(true,  true,        true,        false),  // both pending
        arrayOf(false, true,        false,       false),
        arrayOf(false, false,       true,        false),
        arrayOf(false, true,        true,        false),
    )

    @Test fun testShouldEnqueueRegistrationFullTruthTable() {
        for (row in table) {
            val (enabled, revoke, clear, expected) = row as Array<Any>
            val got = LifecycleGuards.shouldEnqueueRegistration(
                enabled as Boolean, revoke as Boolean, clear as Boolean
            )
            assertEquals(
                "shouldEnqueueRegistration(enabled=$enabled, revoke=$revoke, clear=$clear)",
                expected, got
            )
        }
    }

    @Test fun testCanAcceptTokenSyncDelegatesToShouldEnqueueRegistration() {
        for (row in table) {
            val (enabled, revoke, clear, expected) = row as Array<Any>
            val got = LifecycleGuards.canAcceptTokenSync(
                enabled as Boolean, revoke as Boolean, clear as Boolean
            )
            assertEquals(
                "canAcceptTokenSync(enabled=$enabled, revoke=$revoke, clear=$clear)",
                expected, got
            )
        }
    }
}