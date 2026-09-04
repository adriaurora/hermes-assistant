package dk.foss.jarvis.push

import dk.foss.jarvis.events.HermesEventEnvelope
import dk.foss.jarvis.events.NotificationDeduper
import dk.foss.jarvis.hermes.EventApi
import dk.foss.jarvis.hermes.HermesEvent
import dk.foss.jarvis.hermes.HermesEventsPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushGateTest {
    @Test
    fun `registered event is mapped notified and acknowledged once`() = runBlocking {
        val api = FakeEventApi(Result.success(event("e1")))
        val envelopes = mutableListOf<HermesEventEnvelope>()
        val outcome = gate(api) { envelope, _ -> envelopes += envelope; true }.handlePull("e1")

        assertEquals(GateOutcome.NOTIFIED, outcome)
        assertEquals(listOf("e1"), api.fetched)
        assertEquals(listOf("e1"), api.acked)
        assertEquals(HermesEventEnvelope("e1", "Title", "Body", "session-1", 2, 2.0), envelopes.single())
    }

    @Test
    fun `duplicate push fetches notifies and acknowledges only once`() = runBlocking {
        val api = FakeEventApi(Result.success(event("e1")))
        var notified = 0
        val gate = gate(api) { _, _ -> notified++; true }

        assertEquals(GateOutcome.NOTIFIED, gate.handlePull("e1"))
        assertEquals(GateOutcome.DEDUPED, gate.handlePull("e1"))
        assertEquals(listOf("e1"), api.fetched)
        assertEquals(1, notified)
        assertEquals(listOf("e1"), api.acked)
    }

    @Test
    fun `disabled push does not fetch`() = runBlocking {
        val api = FakeEventApi(Result.success(event("e1")))
        val outcome = gate(api, enabled = false) { _, _ -> error("not called") }.handlePull("e1")
        assertEquals(GateOutcome.DISABLED, outcome)
        assertTrue(api.fetched.isEmpty())
        assertTrue(api.acked.isEmpty())
    }

    @Test
    fun `missing device does not fetch`() = runBlocking {
        val api = FakeEventApi(Result.success(event("e1")))
        val outcome = gate(api, deviceId = null) { _, _ -> error("not called") }.handlePull("e1")
        assertEquals(GateOutcome.NO_DEVICE, outcome)
        assertTrue(api.fetched.isEmpty())
    }

    @Test
    fun `revoked device fetch failure is forgotten and later retry delivers`() = runBlocking {
        val api = FakeEventApi(Result.failure(IllegalStateException("HTTP 404")))
        var notified = 0
        val gate = gate(api) { _, _ -> notified++; true }

        assertEquals(GateOutcome.FETCH_FAILURE, gate.handlePull("e1"))
        api.result = Result.success(event("e1"))
        assertEquals(GateOutcome.NOTIFIED, gate.handlePull("e1"))
        assertEquals(1, notified)
        assertEquals(listOf("e1"), api.acked)
    }

    @Test
    fun `notification rejection is retryable and is not acknowledged`() = runBlocking {
        val api = FakeEventApi(Result.success(event("e1")))
        var allowed = false
        val gate = gate(api) { _, _ -> allowed }
        assertEquals(GateOutcome.DELIVERY_FAILURE, gate.handlePull("e1"))
        assertTrue(api.acked.isEmpty())
        allowed = true
        assertEquals(GateOutcome.NOTIFIED, gate.handlePull("e1"))
    }

    private fun gate(
        api: FakeEventApi,
        enabled: Boolean = true,
        deviceId: String? = "device-1",
        notify: (HermesEventEnvelope, Int) -> Boolean,
    ) = PushGate(PushDeps(enabled, deviceId, api, NotificationDeduper()) { envelope, id ->
        if (notify(envelope, id)) DeliveryOutcome.SUCCESS else DeliveryOutcome.POST_FAILURE
    })

    private fun event(id: String) = HermesEvent(
        event_id = id, event_type = "reminder", created_at = 1.0, available_at = 2.0,
        session_id = "session-1", title = "Title", body = "Body", priority = 2,
    )
}

private class FakeEventApi(var result: Result<HermesEvent>) : EventApi {
    val fetched = mutableListOf<String>()
    val acked = mutableListOf<String>()
    override suspend fun fetchEvent(id: String): Result<HermesEvent> { fetched += id; return result }
    override suspend fun ack(id: String): Result<Unit> { acked += id; return Result.success(Unit) }
    override suspend fun pending(): Result<HermesEventsPage> = Result.success(HermesEventsPage())
}
