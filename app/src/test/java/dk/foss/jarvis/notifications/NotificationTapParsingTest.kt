package dk.foss.jarvis.notifications

import dk.foss.jarvis.hermes.originIdentity
import dk.foss.jarvis.TapRequest
import dk.foss.jarvis.TapRoute
import dk.foss.jarvis.routeTap
import org.junit.Assert.*
import org.junit.Test

class NotificationTapParsingTest {
    @Test fun `different tokens remain different request keys for same session`() {
        val origin = originIdentity("https://h.test", "key-a")
        val a = TapRequest("token-a", TapResult.Valid("session", origin))
        val b = TapRequest("token-b", TapResult.Valid("session", origin))
        assertNotEquals(a.token, b.token)
        assertEquals(TapRoute.Import("session", origin), routeTap(a))
        assertEquals(TapRoute.Import("session", origin), routeTap(b))
    }

    @Test fun `stale route never invokes import callback`() {
        var imported = false
        val route = routeTap(TapRequest("stale", TapResult.StaleOrigin)) { imported = true }
        assertEquals(TapRoute.Stale, route)
        assertFalse(imported)
    }
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
