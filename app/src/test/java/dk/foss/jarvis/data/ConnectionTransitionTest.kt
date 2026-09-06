package dk.foss.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTransitionTest {
    private val registration = DeviceRegistration("device-a", "fcm-token", "http://hermes-a:8642", "old-key")
    private val a = JarvisSettings("HTTP://Hermes-A:8642/", "old-key")

    @Test fun `equivalent origin does not revoke`() {
        val result = ConnectionTransition.decide(a, JarvisSettings("http://hermes-a:8642/api", "new-key"), registration)
        assertFalse(result.revokeRequired)
    }

    @Test fun `A to B requires revoke and preserves A registration`() {
        val result = ConnectionTransition.decide(a, JarvisSettings("http://hermes-b:8642/", "new-key"), registration)
        assertTrue(result.revokeRequired)
        assertEquals(registration, result.preservedRegistration)
        assertEquals("http://hermes-a:8642", result.preservedRegistration?.hermesOrigin)
        assertEquals("old-key", result.preservedRegistration?.apiKey)
    }

    @Test fun `A to B requires revoke even when push is disabled`() {
        val result = ConnectionTransition.decide(a, JarvisSettings("http://hermes-b:8642", "new-key"), registration)
        assertTrue(result.revokeRequired)
    }

    @Test fun `bearer-only change keeps registration`() {
        val result = ConnectionTransition.decide(a, JarvisSettings("http://hermes-a:8642", "new-key"), registration)
        assertFalse(result.revokeRequired)
        assertEquals(registration, result.preservedRegistration)
    }
}
