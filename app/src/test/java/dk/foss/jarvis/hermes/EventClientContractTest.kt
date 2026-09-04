package dk.foss.jarvis.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

class EventClientContractTest {
    @Test fun revokeUsesDeviceDeleteEndpoint() {
        assertEquals("api/devices/device-42", EventClient.deviceRevokePath("device-42"))
    }
}
