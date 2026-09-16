package dk.foss.jarvis.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import dk.foss.jarvis.hermes.originIdentity
import dk.foss.jarvis.ui.HistoryViewModel

/**
 * JVM tests for the [NotificationTapStore] API changes:
 * - Tokens carry full endpoint identity (URL + API key fingerprint).
 * - [consume] validates origin; returns [TapResult].
 * - [peek] is non-destructive.
 * - Legacy tokens (no origin prefix) are rejected.
 */
class NotificationTapStoreTest {

    private lateinit var context: Context
    private val testPrefsKey = "notification_taps"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear tap tokens before each test
        context.getSharedPreferences(testPrefsKey, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun makeOrigin(baseUrl: String, apiKey: String) = originIdentity(baseUrl, apiKey)

    // ─── 1. Valid tap with matching origin ─────────────────────────────────

    @Test fun `consume_with_matching_origin_returns_valid`() = runBlocking {
        val origin = makeOrigin("https://hermes.local/api", "key-a")
        val token = NotificationTapStore.issue(context, "event-1", "session-123", origin)

        val result = NotificationTapStore.consume(context, token, origin)
        assertTrue(result is TapResult.Valid)
        if (result is TapResult.Valid) {
            assertEquals("session-123", result.sessionId)
            assertEquals(origin, result.notificationOrigin)
        }
    }

    // ─── 2. Mismatched origin → StaleOrigin ───────────────────────────────

    @Test fun `consume_with_mismatched_origin_returns_stale_origin`() = runBlocking {
        val originA = makeOrigin("https://hermes.local/api", "key-a")
        val originB = makeOrigin("https://hermes.local/api", "key-b")
        val token = NotificationTapStore.issue(context, "event-2", "session-456", originA)

        val result = NotificationTapStore.consume(context, token, originB)
        assertTrue(result is TapResult.StaleOrigin)
    }

    // ─── 3. Same URL, different API key → different origins ───────────────

    @Test fun `same_url_different_key_produces_different_origin`() = runBlocking {
        val originA = makeOrigin("https://hermes.local/api", "key-a")
        val originB = makeOrigin("https://hermes.local/api", "key-b")
        assertNotEquals("same URL + different key → different origins", originA, originB)

        val token = NotificationTapStore.issue(context, "event-3", "session-789", originA)

        // Tap from originA should NOT be valid when current origin is originB
        val result = NotificationTapStore.consume(context, token, originB)
        assertTrue(result is TapResult.StaleOrigin)
    }

    // ─── 4. Legacy token (2-part, no origin) → StaleOrigin ────────────────

    @Test fun `legacy_format_no_origin_returns_stale_origin`() = runBlocking {
        // Legacy format: "eventId\0sessionId" (2 parts, no origin prefix)
        val prefs = context.getSharedPreferences(testPrefsKey, Context.MODE_PRIVATE)
        val token = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(token, "legacy-event\u0000legacy-session").apply()

        val result = NotificationTapStore.consume(context, token, "current-origin")
        assertTrue(result is TapResult.StaleOrigin)
    }

    // ─── 5. Missing token → NotFound ──────────────────────────────────────

    @Test fun `consume_missing_token_returns_not_found`() = runBlocking {
        val result = NotificationTapStore.consume(context, "non-existent-token", "some-origin")
        assertTrue(result is TapResult.NotFound)
    }

    // ─── 6. Peek is non-destructive ───────────────────────────────────────

    @Test fun `peek_does_not_consume_token`() = runBlocking {
        val origin = makeOrigin("https://hermes.local/api", "key-a")
        val token = NotificationTapStore.issue(context, "event-4", "session-a", origin)

        // First peek: should return Valid
        val peek1 = NotificationTapStore.peek(context, token, origin)
        assertTrue(peek1 is TapResult.Valid)
        if (peek1 is TapResult.Valid) assertEquals("session-a", peek1.sessionId)

        // Second peek: should STILL return Valid (token not consumed)
        val peek2 = NotificationTapStore.peek(context, token, origin)
        assertTrue(peek2 is TapResult.Valid)
        if (peek2 is TapResult.Valid) assertEquals("session-a", peek2.sessionId)

        // Now consume — should succeed
        val consumed = NotificationTapStore.consume(context, token, origin)
        assertTrue(consumed is TapResult.Valid)

        // Third peek: should be NotFound (token already consumed)
        val peek3 = NotificationTapStore.peek(context, token, origin)
        assertTrue(peek3 is TapResult.NotFound)
    }

    // ─── 7. Same origin, different events → Valid ─────────────────────────

    @Test fun `same_origin_different_events_are_valid`() = runBlocking {
        val origin = makeOrigin("https://hermes.local/api", "key-a")
        val token1 = NotificationTapStore.issue(context, "event-a", "session-1", origin)
        val token2 = NotificationTapStore.issue(context, "event-b", "session-2", origin)

        val r1 = NotificationTapStore.consume(context, token1, origin)
        assertTrue(r1 is TapResult.Valid)

        val r2 = NotificationTapStore.consume(context, token2, origin)
        assertTrue(r2 is TapResult.Valid)
    }

    // ─── 8. Unconfigured current origin → all taps stale ──────────────────

    @Test fun `unconfigured_origin_makes_all_taps_stale`() = runBlocking {
        val origin = makeOrigin("https://hermes.local/api", "key-a")
        val token = NotificationTapStore.issue(context, "event-5", "session-x", origin)

        val result = NotificationTapStore.consume(context, token, "")
        assertTrue(result is TapResult.StaleOrigin)
    }

    // ─── 9. Consume removes token ─────────────────────────────────────────

    @Test fun `consume_removes_token_from_store`() = runBlocking {
        val origin = makeOrigin("https://hermes.local/api", "key-a")
        val token = NotificationTapStore.issue(context, "event-6", "session-y", origin)

        // First consume succeeds
        val r1 = NotificationTapStore.consume(context, token, origin)
        assertTrue(r1 is TapResult.Valid)

        // Second consume fails (token was removed)
        val r2 = NotificationTapStore.consume(context, token, origin)
        assertTrue(r2 is TapResult.NotFound)
    }

    // ─── 10. Null session ID → NotFound ───────────────────────────────────

    @Test fun `tap_with_null_session_id_returns_not_found`() = runBlocking {
        val origin = makeOrigin("https://hermes.local/api", "key-a")
        val token = NotificationTapStore.issue(context, "event-7", null, origin)

        val result = NotificationTapStore.consume(context, token, origin)
        assertTrue(result is TapResult.NotFound)
    }
}