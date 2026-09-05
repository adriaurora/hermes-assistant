package dk.foss.jarvis.notifications

import dk.foss.jarvis.events.EventMapper
import dk.foss.jarvis.events.NotificationDeduper
import dk.foss.jarvis.events.RetryPolicy
import dk.foss.jarvis.events.HermesEventEnvelope
import dk.foss.jarvis.events.StableNotificationId
import dk.foss.jarvis.hermes.EventApi
import dk.foss.jarvis.hermes.HermesEvent
import dk.foss.jarvis.hermes.HermesEventsPage
import dk.foss.jarvis.push.isValidHermesEventId

/** Fetches, deduplicates, and acknowledges durable events; deliberately has no Android dependency. */
class EventFetcher(
    private val client: EventApi,
    private val deduper: NotificationDeduper,
    private val deliver: (HermesEventEnvelope) -> Boolean = { true },
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    suspend fun onPushWoken(eventId: String): Result<Boolean> {
        if (!isValidHermesEventId(eventId)) return Result.failure(IllegalArgumentException("invalid event id"))
        if (deduper.isAckPending(eventId)) {
            return client.ack(eventId).fold(
                onSuccess = { deduper.clearAckPending(eventId); Result.success(false) },
                onFailure = { Result.failure(it) },
            )
        }
        if (deduper.observe(eventId)) return Result.success(false)
        val event = client.fetchEvent(eventId).getOrElse {
            deduper.forget(eventId)
            return Result.failure(it)
        }
        val envelope = EventMapper.toEnvelope(event)
        // Keep the clock dependency available for callers that enforce expiry at presentation time.
        now()
        try {
            if (!deliver(envelope)) {
                deduper.forget(eventId)
                return Result.failure(IllegalStateException("notification delivery failed"))
            }
        } catch (failure: Throwable) {
            // Never leave an event permanently deduped when delivery itself throws.
            deduper.forget(eventId)
            throw failure
        }
        return client.ack(eventId).fold(
            onSuccess = { Result.success(true) },
            onFailure = { deduper.markAckPending(eventId); Result.failure(it) },
        )
    }

    suspend fun onPendingSync(): Result<Int> = client.pending().fold(
        onSuccess = { page ->
            var delivered = 0
            var failure: Throwable? = null
            page.events.forEach { event ->
                val result = onPushWoken(event.event_id)
                if (result.isSuccess && result.getOrDefault(false)) delivered++
                if (result.isFailure && failure == null) failure = result.exceptionOrNull()
            }
            failure?.let { Result.failure(it) } ?: Result.success(delivered)
        },
        onFailure = { Result.failure(it) },
    )
}

/** Materializes fetched envelopes for Android while keeping EventFetcher JVM-testable. */
class EventDispatcher(
    private val client: EventApi,
    private val deduper: NotificationDeduper = NotificationDeduper(),
    private val notify: (HermesEventEnvelope, Int) -> Unit,
) {
    private val fetcher = EventFetcher(client, deduper)

    suspend fun onPushWoken(eventId: String): Result<Boolean> {
        if (!isValidHermesEventId(eventId)) return Result.failure(IllegalArgumentException("invalid event id"))
        if (deduper.isAckPending(eventId)) {
            return client.ack(eventId).fold(
                onSuccess = { deduper.clearAckPending(eventId); Result.success(false) },
                onFailure = { Result.failure(it) },
            )
        }
        if (deduper.observe(eventId)) return Result.success(false)
        val event = client.fetchEvent(eventId).getOrElse {
            deduper.forget(eventId)
            return Result.failure(it)
        }
        val envelope = EventMapper.toEnvelope(event)
        try { notify(envelope, StableNotificationId.forEvent(eventId)) }
        catch (failure: Throwable) {
            deduper.forget(eventId)
            return Result.failure(failure)
        }
        return client.ack(eventId).fold(
            onSuccess = { Result.success(true) },
            onFailure = { deduper.markAckPending(eventId); Result.failure(it) },
        )
    }

    suspend fun onPendingSync(): Result<Int> = client.pending().fold(
        onSuccess = { page ->
            var count = 0
            var failure: Throwable? = null
            page.events.forEach {
                val result = onPushWoken(it.event_id)
                if (result.isSuccess && result.getOrDefault(false)) count++
                if (result.isFailure && failure == null) failure = result.exceptionOrNull()
            }
            failure?.let { Result.failure(it) } ?: Result.success(count)
        },
        onFailure = { Result.failure(it) },
    )

    fun shouldRetry(fetchFailed: Boolean, attempt: Int): Boolean =
        RetryPolicy.shouldRetryOnReconnect(fetchFailed, fetchFailed, attempt)
}

class RetryGate {
    fun decideRetry(fetchFailed: Boolean, attempt: Int): Boolean =
        RetryPolicy.shouldRetryOnReconnect(fetchFailed, fetchFailed, attempt)
}
