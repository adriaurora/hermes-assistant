package dk.foss.jarvis.push

import dk.foss.jarvis.events.HermesEventEnvelope
import dk.foss.jarvis.events.NotificationDeduper
import dk.foss.jarvis.hermes.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushGateHardeningTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"

    @Test fun expiredEventsAreAcknowledgedWithoutNotification() = runBlocking {
        val api = Api(HermesEvent(id, "reminder", 1.0, 1.0, expires_at = 9.0))
        var posted = false
        val result = gate(api, { posted = true }, now = { 10.0 }).handlePull(id)
        assertEquals(GateOutcome.ACKED, result)
        assertTrue(!posted)
        assertEquals(listOf(id), api.acks)
    }

    @Test fun eventForAnotherDeviceIsRejected() = runBlocking {
        val api = Api(HermesEvent(id, "reminder", 1.0, 1.0, device_id = "other"))
        val result = gate(api, {}, now = { 2.0 }).handlePull(id)
        assertEquals(GateOutcome.FETCH_PERMANENT, result)
        assertTrue(api.acks.isEmpty())
    }

    private fun gate(api: Api, post: () -> Unit, now: () -> Double) = PushGate(
        PushDeps(true, "device-1", api, NotificationDeduper(), now = now) { _, _ ->
            post(); DeliveryOutcome.SUCCESS
        },
    )

    private class Api(private val event: HermesEvent) : EventApi {
        val acks = mutableListOf<String>()
        override suspend fun fetchEvent(id: String) = Result.success(event)
        override suspend fun ack(id: String): Result<Unit> { acks += id; return Result.success(Unit) }
        override suspend fun pending() = Result.success(HermesEventsPage())
    }
}
