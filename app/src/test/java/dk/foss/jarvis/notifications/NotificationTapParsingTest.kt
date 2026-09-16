package dk.foss.jarvis.notifications

import dk.foss.jarvis.hermes.originIdentity
import org.junit.Assert.*
import org.junit.Test

class NotificationTapParsingTest {
    @Test fun `matching origin parses valid session`() {
        val origin = originIdentity("https://h.test", "key-a")
        assertEquals(TapResult.Valid("session", origin), parseNotificationTap("$origin\u0000event\u0000session", origin))
    }
    @Test fun `same URL different key is stale`() {
        val a = originIdentity("https://h.test", "key-a"); val b = originIdentity("https://h.test", "key-b")
        assertEquals(TapResult.StaleOrigin, parseNotificationTap("$a\u0000e\u0000s", b))
    }
    @Test fun `legacy and corrupt values are stale`() {
        assertEquals(TapResult.StaleOrigin, parseNotificationTap("event\u0000session", "origin"))
        assertEquals(TapResult.StaleOrigin, parseNotificationTap("garbage", "origin"))
    }
}
