package dk.foss.jarvis.notifications

import dk.foss.jarvis.hermes.originIdentity
import org.junit.Assert.*
import org.junit.Test

class NotificationTapStoreJvmTest {
    private class Memory : NotificationTapStorage {
        val values = mutableMapOf<String, String>()
        override fun get(token: String) = values[token]
        override fun remove(token: String) { values.remove(token) }
    }
    @Test fun `consume valid removes token and peek does not`() {
        val s = Memory(); val o = originIdentity("https://h", "k"); s.values["t"] = "$o\u0000e\u0000session"
        assertEquals(TapResult.Valid("session", o), NotificationTapStore.peek(s, "t", o)); assertTrue(s.values.containsKey("t"))
        assertEquals(TapResult.Valid("session", o), NotificationTapStore.consume(s, "t", o)); assertFalse(s.values.containsKey("t"))
    }
    @Test fun `legacy and corrupt consume stale and remove`() {
        val s = Memory(); s.values["legacy"] = "event\u0000session"; s.values["bad"] = "garbage"
        assertEquals(TapResult.StaleOrigin, NotificationTapStore.consume(s, "legacy", "o")); assertFalse(s.values.containsKey("legacy"))
        assertEquals(TapResult.StaleOrigin, NotificationTapStore.consume(s, "bad", "o")); assertFalse(s.values.containsKey("bad"))
    }
}
