package dk.foss.jarvis.push

import org.junit.Test
import org.junit.Assert.*

/**
 * Verifies that push plugin unavailability is handled correctly:
 * - PushTransport.V1 is the only transport (no LEGACY)
 * - EnrollmentPolicy.decide works with V1-only logic
 */
class PushPluginUnavailableTest {

    @Test
    fun `PushTransport only has V1`() {
        assertEquals("v1", PushTransport.V1)
        // LEGACY should not exist
        try {
            // This compiles to check that PushTransport.LEGACY doesn't exist
            val clazz = Class.forName("dk.foss.jarvis.push.PushTransport")
            val field = clazz.getField("LEGACY")
            fail("PushTransport.LEGACY should not exist")
        } catch (e: NoSuchFieldException) {
            // Expected
        } catch (e: ClassNotFoundException) {
            // PushTransport class doesn't exist — also fine
        }
    }

    @Test
    fun `EnrollmentPolicy no registration`() {
        val action = EnrollmentPolicy.decide(false, false)
        assertEquals(EnrollmentAction.RegisterFresh::class, action::class)
    }

    @Test
    fun `EnrollmentPolicy has registration no secret returns None`() {
        val action = EnrollmentPolicy.decide(true, false)
        assertEquals(EnrollmentAction.None, action)
    }

    @Test
    fun `EnrollmentPolicy has registration and secret`() {
        val action = EnrollmentPolicy.decide(true, true)
        assertEquals(EnrollmentAction.UpdateToken, action)
    }

    @Test
    fun `TransportSelector always returns V1`() = kotlinx.coroutines.runBlocking {
        val store = object : TransportChoiceStore {
            override suspend fun choice(): String? = null
            override suspend fun lastProbe(): String? = null
            override suspend fun lastProbeAt(): Long? = null
            override suspend fun recordProbe(transport: String, nowMs: Long) {}
        }
        val selector = TransportSelector(store) { _, _ -> null }
        val result = selector.select("http://localhost", "key")
        assertEquals(PushTransport.V1, result)
    }

    @Test
    fun `TransportDecision always returns V1`() {
        assertEquals(PushTransport.V1, TransportDecision.decide("auto", null, null, null))
        assertEquals(PushTransport.V1, TransportDecision.decide("legacy", null, null, null))
        assertEquals(PushTransport.V1, TransportDecision.decide("v1", null, null, null))
    }
}
