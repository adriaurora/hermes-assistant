package dk.foss.jarvis.push

import dk.foss.jarvis.events.HermesEventEnvelope
import dk.foss.jarvis.events.NotificationDeduper
import dk.foss.jarvis.events.EventMapper
import dk.foss.jarvis.events.StableNotificationId
import dk.foss.jarvis.hermes.EventApi

data class PushDeps(
    val enabled: Boolean,
    val registeredDeviceId: String?,
    val client: EventApi,
    val deduper: NotificationDeduper,
    val notify: (HermesEventEnvelope, Int) -> DeliveryOutcome,
)

enum class DeliveryOutcome { SUCCESS, PERMISSION_DENIED, POST_FAILURE }
enum class GateOutcome { NOTIFIED, ACKED, DEDUPED, DISABLED, NO_DEVICE, FETCH_FAILURE, DELIVERY_FAILURE, ACK_FAILURE }

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
        if (deps.deduper.observe(eventId)) return GateOutcome.DEDUPED
        val event = deps.client.fetchEvent(eventId).getOrElse {
            deps.deduper.forget(eventId)
            return GateOutcome.FETCH_FAILURE
        }
        val envelope = EventMapper.toEnvelope(event)
        if (deps.notify(envelope, StableNotificationId.forEvent(eventId)) != DeliveryOutcome.SUCCESS) {
            deps.deduper.forget(eventId)
            return GateOutcome.DELIVERY_FAILURE
        }
        return if (deps.client.ack(eventId).isSuccess) GateOutcome.NOTIFIED
        else { deps.deduper.markAckPending(eventId); GateOutcome.ACK_FAILURE }
    }
}
