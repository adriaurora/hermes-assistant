package dk.foss.jarvis.push

import dk.foss.jarvis.events.HermesEventEnvelope
import dk.foss.jarvis.events.NotificationDeduper
import dk.foss.jarvis.events.EventMapper
import dk.foss.jarvis.events.StableNotificationId
import dk.foss.jarvis.hermes.EventApi
import dk.foss.jarvis.hermes.EventFetchException
import dk.foss.jarvis.hermes.FetchFailureKind

data class PushDeps(
    val enabled: Boolean,
    val registeredDeviceId: String?,
    val client: EventApi,
    val deduper: NotificationDeduper,
    val wasDelivered: suspend (String) -> Boolean = { false },
    val onDelivered: suspend (String) -> Unit = {},
    val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
    val onDeliveryRejected: suspend (String) -> Unit = {},
    val notify: (HermesEventEnvelope, Int) -> DeliveryOutcome,
)

enum class DeliveryOutcome { SUCCESS, PERMISSION_DENIED, POST_FAILURE }
enum class GateOutcome { NOTIFIED, ACKED, DEDUPED, DISABLED, NO_DEVICE, FETCH_FAILURE, FETCH_PERMANENT, DELIVERY_FAILURE, PERMISSION_DENIED, ACK_FAILURE }

class PushGate(private val deps: PushDeps) {
    suspend fun handlePull(eventId: String): GateOutcome {
        if (!deps.enabled) return GateOutcome.DISABLED
        if (deps.registeredDeviceId.isNullOrBlank()) return GateOutcome.NO_DEVICE
        if (!isValidHermesEventId(eventId)) return GateOutcome.FETCH_FAILURE
        if (deps.deduper.isAckPending(eventId)) {
            return if (deps.client.ack(eventId).isSuccess) {
                deps.deduper.clearAckPending(eventId); GateOutcome.ACKED
            } else GateOutcome.ACK_FAILURE
        }
        if (deps.wasDelivered(eventId)) return if (deps.client.ack(eventId).isSuccess) GateOutcome.ACKED else GateOutcome.ACK_FAILURE
        if (deps.deduper.observe(eventId)) return GateOutcome.DEDUPED
        val event = deps.client.fetchEvent(eventId).getOrElse {
            val err = it
            deps.deduper.forget(eventId)
            return if ((err as? dk.foss.jarvis.hermes.EventFetchException)?.rpcCode == "event_not_found") GateOutcome.FETCH_PERMANENT else GateOutcome.FETCH_FAILURE
        }
        // The event is authenticated by the device-bound API response.  Do not
        // allow a valid event id to deliver an event belonging to another
        // device (null is retained for old Hermes servers).
        if (event.event_id != eventId ||
            (event.device_id != null && event.device_id != deps.registeredDeviceId)) {
            deps.deduper.forget(eventId)
            return GateOutcome.FETCH_PERMANENT
        }
        if (event.expires_at != null && event.expires_at <= deps.now()) {
            deps.deduper.forget(eventId)
            return if (deps.client.ack(eventId).isSuccess) GateOutcome.ACKED else GateOutcome.ACK_FAILURE
        }
        val envelope = EventMapper.toEnvelope(event)
        // Persist the delivered marker before posting.  This is intentional:
        // a process death between notify() and persistence must never produce
        // a second user-visible notification.  ACK is retried independently.
        deps.onDelivered(eventId)
        val delivery = deps.notify(envelope, StableNotificationId.forEvent(eventId))
        if (delivery != DeliveryOutcome.SUCCESS) {
            // Permission denial/post failure is recoverable. It must not ACK,
            // and denial must not leave a durable reservation or cause retries.
            deps.onDeliveryRejected(eventId)
            deps.deduper.forget(eventId)
            return if (delivery == DeliveryOutcome.PERMISSION_DENIED) GateOutcome.PERMISSION_DENIED else GateOutcome.DELIVERY_FAILURE
        }
        return if (deps.client.ack(eventId).isSuccess) GateOutcome.NOTIFIED
        else { deps.deduper.markAckPending(eventId); GateOutcome.ACK_FAILURE }
    }
}
