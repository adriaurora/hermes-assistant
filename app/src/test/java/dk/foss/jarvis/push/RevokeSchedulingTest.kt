package dk.foss.jarvis.push

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that FcmRevokeWorker uses KEEP so that concurrent
 * schedule() calls do not replace an in-flight revoke with exponential
 * back-off.
 */
class RevokeSchedulingTest {

    @Test fun testRevokeExistingWorkPolicyIsKeep() {
        // Direct constant check.  Using .name avoids needing ExistingWorkPolicy
        // on the JVM classpath if the Android SDK isn't present.
        val name = FcmRevokeWorker.REVOKE_EXISTING_WORK_POLICY.name
        assertEquals("KEEP", name)
    }
}