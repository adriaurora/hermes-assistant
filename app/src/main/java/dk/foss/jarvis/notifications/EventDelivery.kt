package dk.foss.jarvis.notifications

import dk.foss.jarvis.events.EventMapper
import dk.foss.jarvis.events.NotificationDeduper
import dk.foss.jarvis.events.RetryPolicy
import dk.foss.jarvis.events.HermesEventEnvelope
import dk.foss.jarvis.events.StableNotificationId
import dk.foss.jarvis.hermes.EventApi
import dk.foss.jarvis.hermes.HermesEvent
import dk.foss.jarvis.hermes.HermesEventsPage

/** Fetches, deduplicates, and acknowledges durable events; deliberately has no Android dependency. */
class EventFetcher(
    private val client: EventApi,
    private val deduper: NotificationDeduper,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    suspend fun onPushWoken(eventId: String): Result<Boolean> {
        if (deduper.observe(eventId)) return Result.success(false)
        val event = client.fetchEvent(eventId).getOrElse {
            deduper.forget(eventId)
            return Result.failure(it)
        }
        EventMapper.toEnvelope(event)
        // Keep the clock dependency available for callers that enforce expiry at presentation time.
        now()
        client.ack(eventId) // acknowledgement is best effort; delivery already succeeded
        return Result.success(true)
    }

    suspend fun onPendingSync(): Result<Int> = client.pending().map { page ->
        var delivered = 0
        page.events.forEach { if (onPushWoken(it.event_id).getOrDefault(false)) delivered++ }
        delivered
    }
}

/** Materializes fetched envelopes for Android while keeping EventFetcher JVM-testable. */
class EventDispatcher(
    private val client: EventApi,
    private val deduper: NotificationDeduper = NotificationDeduper(),
    private val notify: (HermesEventEnvelope, Int) -> Unit,
) {
    private val fetcher = EventFetcher(client, deduper)

    suspend fun onPushWoken(eventId: String): Result<Boolean> {
        if (deduper.observe(eventId)) return Result.success(false)
        val event = client.fetchEvent(eventId).getOrElse {
            deduper.forget(eventId)
            return Result.failure(it)
        }
        val envelope = EventMapper.toEnvelope(event)
        notify(envelope, StableNotificationId.forEvent(eventId))
        client.ack(eventId)
        return Result.success(true)
    }

    suspend fun onPendingSync(): Result<Int> = client.pending().map { page ->
        var count = 0
        page.events.forEach { if (onPushWoken(it.event_id).getOrDefault(false)) count++ }
        count
    }

    fun shouldRetry(fetchFailed: Boolean, attempt: Int): Boolean =
        RetryPolicy.shouldRetryOnReconnect(fetchFailed, fetchFailed, attempt)
}

class RetryGate {
    fun decideRetry(fetchFailed: Boolean, attempt: Int): Boolean =
        RetryPolicy.shouldRetryOnReconnect(fetchFailed, fetchFailed, attempt)
}
