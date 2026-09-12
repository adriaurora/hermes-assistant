package dk.foss.jarvis.push

import org.junit.Assert.assertEquals
import org.junit.Test

class V1PolicyTest {

    // ── EnrollmentPolicy ──────────────────────────────────────────────────

    @Test
    fun `no registration returns RegisterFresh`() {
        val action = EnrollmentPolicy.decide(false, false)
        assertEquals(EnrollmentAction.RegisterFresh::class, action::class)
    }

    @Test
    fun `has registration with secret returns UpdateToken`() {
        val action = EnrollmentPolicy.decide(true, true)
        assertEquals(EnrollmentAction.UpdateToken, action)
    }

    @Test
    fun `has registration no secret returns None`() {
        val action = EnrollmentPolicy.decide(true, false)
        assertEquals(EnrollmentAction.None, action)
    }

    @Test
    fun `has registration and secret returns UpdateToken`() {
        assertEquals(EnrollmentAction.UpdateToken, EnrollmentPolicy.decide(true, true))
    }
}