package dk.foss.jarvis.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

class EventClientOriginTest {
    @Test fun `origin identity ignores trailing slash and default port`() {
        assertEquals(
            EventClient.originIdentity("HTTPS://Hermes.local/"),
            EventClient.originIdentity("https://hermes.local:443/api"),
        )
    }

    @Test fun `origin identity distinguishes host and port`() {
        assertEquals(false,
            EventClient.originIdentity("http://hermes-a:8642") ==
                EventClient.originIdentity("http://hermes-b:8642"))
        assertEquals(false,
            EventClient.originIdentity("http://hermes-a:8642") ==
                EventClient.originIdentity("http://hermes-a:8643"))
    }
}
