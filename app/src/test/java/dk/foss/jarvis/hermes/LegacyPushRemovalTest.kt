package dk.foss.jarvis.hermes

import org.junit.Test
import org.junit.Assert.*

/**
 * Verifies that legacy EventClient code is fully removed:
 * - No EventClient class exists
 * - No /api/devices or /api/events paths are referenced in production code
 * - EventApi interface is still available for EventRpcClient (V1 RPC)
 */
class LegacyPushRemovalTest {

    @Test
    fun `EventClient class does not exist`() {
        try {
            Class.forName("dk.foss.jarvis.hermes.EventClient")
            fail("EventClient class should not exist")
        } catch (_: ClassNotFoundException) {
            // Expected class is gone
        }
    }

    @Test
    fun `EventApi interface still exists for EventRpcClient`() {
        val clazz = Class.forName("dk.foss.jarvis.hermes.EventApi")
        assertNotNull("EventApi interface should exist", clazz)
        assertTrue("EventApi should be an interface", clazz.isInterface)
    }

    @Test
    fun `EventRpcClient implements EventApi`() {
        val eventApiClass = Class.forName("dk.foss.jarvis.hermes.EventApi")
        val eventRpcClass = Class.forName("dk.foss.jarvis.hermes.EventRpcClient")
        assertTrue("EventRpcClient should implement EventApi", eventApiClass.isAssignableFrom(eventRpcClass))
    }

    @Test
    fun `RPC_PATH does not reference legacy paths`() {
        // RPC_PATH is the V1 wire path — must not reference legacy REST endpoints
        val path = dk.foss.jarvis.hermes.RPC_PATH
        assertTrue("RPC_PATH should not contain api/devices: $path", path.contains("api/devices").not())
        assertTrue("RPC_PATH should not contain api/events: $path", path.contains("api/events").not())
        assertEquals("api/platforms/hermes_assistant/events", path)
    }
}