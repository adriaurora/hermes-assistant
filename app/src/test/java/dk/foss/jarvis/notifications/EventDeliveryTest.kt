package dk.foss.jarvis.notifications

import dk.foss.jarvis.events.RetryPolicy
import dk.foss.jarvis.events.StableNotificationId
import dk.foss.jarvis.hermes.EventApi
import dk.foss.jarvis.hermes.HermesEvent
import dk.foss.jarvis.hermes.HermesEventsPage
import dk.foss.jarvis.hermes.HermesJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDeliveryTest {
    @Test
    fun `push delivery deduplicates and acknowledges once`() = runBlocking {
        val api = FakeEventApi(events = mapOf("123e4567-e89b-12d3-a456-426614174000" to Result.success(event("123e4567-e89b-12d3-a456-426614174000"))))
        val notifications = mutableListOf<String>()
        val dispatcher = EventDispatcher(api) { envelope, _ -> notifications += envelope.eventId }

        assertTrue(dispatcher.onPushWoken("123e4567-e89b-12d3-a456-426614174000").getOrThrow())
        assertFalse(dispatcher.onPushWoken("123e4567-e89b-12d3-a456-426614174000").getOrThrow())
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000"), notifications)
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000"), api.acked)
    }

    @Test
    fun `fetch failure is returned and forgotten for a later retry`() = runBlocking {
        val failure = IllegalStateException("network down")
        val api = FakeEventApi(events = mapOf("123e4567-e89b-12d3-a456-426614174000" to Result.failure(failure)))
        var notifications = 0
        val dispatcher = EventDispatcher(api) { _, _ -> notifications++ }

        assertEquals(failure, dispatcher.onPushWoken("123e4567-e89b-12d3-a456-426614174000").exceptionOrNull())
        assertTrue(api.acked.isEmpty())
        api.events["123e4567-e89b-12d3-a456-426614174000"] = Result.success(event("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(dispatcher.onPushWoken("123e4567-e89b-12d3-a456-426614174000").getOrThrow())
        assertEquals(1, notifications)
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000"), api.acked)
    }

    @Test
    fun `ack failure remains retryable without duplicate notification`() = runBlocking {
        val api = FakeEventApi(
            events = mapOf("123e4567-e89b-12d3-a456-426614174000" to Result.success(event("123e4567-e89b-12d3-a456-426614174000"))),
            ackResult = Result.failure(IllegalStateException("ack unavailable")),
        )
        var notifications = 0
        val dispatcher = EventDispatcher(api) { _, _ -> notifications++ }

        assertTrue(dispatcher.onPushWoken("123e4567-e89b-12d3-a456-426614174000").isFailure)
        assertEquals(1, notifications)
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000"), api.acked)
        assertTrue(dispatcher.onPushWoken("123e4567-e89b-12d3-a456-426614174000").isFailure)
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-426614174000"), api.acked)
    }

    @Test
    fun `unknown event is retryable after failed fetch`() = runBlocking {
        val api = FakeEventApi(events = mutableMapOf("123e4567-e89b-12d3-a456-426614174002" to Result.failure(Exception("HTTP 404"))))
        val notifications = mutableListOf<String>()
        val dispatcher = EventDispatcher(api) { envelope, _ -> notifications += envelope.eventId }

        assertTrue(dispatcher.onPushWoken("123e4567-e89b-12d3-a456-426614174002").isFailure)
        assertTrue(notifications.isEmpty())
        assertTrue(api.acked.isEmpty())
        api.events["123e4567-e89b-12d3-a456-426614174002"] = Result.success(event("123e4567-e89b-12d3-a456-426614174002"))
        assertTrue(dispatcher.onPushWoken("123e4567-e89b-12d3-a456-426614174002").getOrThrow())
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174002"), notifications)
    }

    @Test
    fun `pending sync delivers each event once`() = runBlocking {
        val api = FakeEventApi(
            events = mapOf("123e4567-e89b-12d3-a456-426614174000" to Result.success(event("123e4567-e89b-12d3-a456-426614174000")), "123e4567-e89b-12d3-a456-426614174001" to Result.success(event("123e4567-e89b-12d3-a456-426614174001"))),
            pendingResult = Result.success(HermesEventsPage(listOf(event("123e4567-e89b-12d3-a456-426614174000"), event("123e4567-e89b-12d3-a456-426614174001")))),
        )
        val notifications = mutableListOf<String>()
        val dispatcher = EventDispatcher(api) { envelope, _ -> notifications += envelope.eventId }

        assertEquals(2, dispatcher.onPendingSync().getOrThrow())
        assertEquals(0, dispatcher.onPendingSync().getOrThrow())
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-426614174001"), notifications)
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-426614174001"), api.acked)
    }

    @Test
    fun `retry gate and retry policy use bounded failed pending table`() {
        val gate = RetryGate()
        assertTrue(gate.decideRetry(true, 0))
        assertTrue(gate.decideRetry(true, 1))
        assertFalse(gate.decideRetry(true, RetryPolicy.MAX_RETRIES))
        assertFalse(gate.decideRetry(false, 0))

        assertTrue(RetryPolicy.shouldRetryOnReconnect(true, true, 0))
        assertFalse(RetryPolicy.shouldRetryOnReconnect(true, true, RetryPolicy.MAX_RETRIES))
        assertFalse(RetryPolicy.shouldRetryOnReconnect(true, false, 0))
        assertFalse(RetryPolicy.shouldRetryOnReconnect(false, true, 0))
        assertEquals(2, RetryPolicy.MAX_RETRIES)
    }

    @Test
    fun `stable notification ids remain deterministic`() {
        assertEquals(StableNotificationId.forEvent("event-42"), StableNotificationId.forEvent("event-42"))
        assertEquals(StableNotificationId.forEvent("event-42"), StableNotificationId.forEvent("event-42"))
    }

    @Test
    fun `event and pending page decode with the production HermesJson`() {
        val json = """
            {"event_id":"evt-1","event_type":"reminder","created_at":1787382860.47,
             "available_at":1787382900.25,"expires_at":1787386500.0,"source":"cron",
             "source_id":"job-7","session_id":"session-1","title":"Meeting",
             "body":"Stand-up in five minutes","priority":2,"status":"pending","device_id":"dev-1"}
        """.trimIndent()
        val decoded = HermesJson.decodeFromString(HermesEvent.serializer(), json)
        val page = HermesJson.decodeFromString(
            HermesEventsPage.serializer(),
            """{"events":[$json,${json.replace("evt-1", "evt-2") }]}""",
        )

        assertEquals("evt-1", decoded.event_id)
        assertEquals("Meeting", decoded.title)
        assertEquals("Stand-up in five minutes", decoded.body)
        assertEquals("session-1", decoded.session_id)
        assertEquals(2, decoded.priority)
        assertEquals(2, page.events.size)
        assertEquals("evt-2", page.events[1].event_id)
    }

    private fun event(id: String) = HermesEvent(
        event_id = id,
        event_type = "reminder",
        created_at = 1.0,
        available_at = 2.0,
        title = "Title $id",
        body = "Body $id",
    )
}

private class FakeEventApi(
    events: Map<String, Result<HermesEvent>>,
    var pendingResult: Result<HermesEventsPage> = Result.success(HermesEventsPage()),
    private val ackResult: Result<Unit> = Result.success(Unit),
) : EventApi {
    val events = events.toMutableMap()
    val acked = mutableListOf<String>()

    override suspend fun fetchEvent(id: String): Result<HermesEvent> =
        events[id] ?: Result.failure(IllegalArgumentException("unknown event: $id"))

    override suspend fun ack(id: String): Result<Unit> {
        acked += id
        return ackResult
    }

    override suspend fun pending(): Result<HermesEventsPage> = pendingResult
}
