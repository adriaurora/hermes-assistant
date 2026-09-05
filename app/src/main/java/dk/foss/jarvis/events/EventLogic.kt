package dk.foss.jarvis.events

import dk.foss.jarvis.hermes.HermesEvent

data class HermesEventEnvelope(
    val eventId: String,
    val title: String,
    val body: String,
    val sessionId: String?,
    val priority: Int,
    val availableAt: Double,
)

object EventMapper {
    fun toEnvelope(event: HermesEvent) = HermesEventEnvelope(
        eventId = event.event_id,
        title = event.title.orEmpty(),
        body = event.body.orEmpty(),
        sessionId = event.session_id,
        priority = event.priority,
        availableAt = event.available_at,
    )
}

class NotificationDeduper(private val maxEntries: Int = 1024) {
    private val seen = LinkedHashSet<String>()
    private val ackPending = LinkedHashSet<String>()
    @Synchronized fun observe(eventId: String): Boolean {
        val duplicate = !seen.add(eventId)
        trim()
        return duplicate
    }
    @Synchronized fun forget(eventId: String) { seen.remove(eventId) }
    @Synchronized fun markAckPending(eventId: String) { ackPending.add(eventId) }
    @Synchronized fun isAckPending(eventId: String): Boolean = ackPending.contains(eventId)
    @Synchronized fun clearAckPending(eventId: String) { ackPending.remove(eventId) }
    @Synchronized private fun trim() { while (seen.size > maxEntries) seen.iterator().apply { next(); remove() } }
}

object StableNotificationId {
    /** Java's specified String hash is deterministic across processes and platforms. */
    fun forEvent(eventId: String): Int = eventId.hashCode()
}

object RetryPolicy {
    const val MAX_RETRIES = 2
    fun shouldRetryOnReconnect(eventsPending: Boolean, lastFetchFailed: Boolean, retriesSoFar: Int): Boolean =
        eventsPending && lastFetchFailed && retriesSoFar < MAX_RETRIES
}
