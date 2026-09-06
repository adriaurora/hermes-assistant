package dk.foss.jarvis.push

import dk.foss.jarvis.events.HermesEventEnvelope
import dk.foss.jarvis.events.NotificationDeduper
import dk.foss.jarvis.hermes.EventApi
import dk.foss.jarvis.hermes.EventFetchException
import dk.foss.jarvis.hermes.FetchFailureKind
import dk.foss.jarvis.hermes.HermesEvent
import dk.foss.jarvis.hermes.HermesEventsPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushGateTest {
    @Test
    fun `registered event is mapped notified and acknowledged once`() = runBlocking {
        val api = FakeEventApi(Result.success(event("123e4567-e89b-12d3-a456-426614174000")))
        val envelopes = mutableListOf<HermesEventEnvelope>()
        val outcome = gate(api) { envelope, _ -> envelopes += envelope; true }.handlePull("123e4567-e89b-12d3-a456-426614174000")

        assertEquals(GateOutcome.NOTIFIED, outcome)
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000"), api.fetched)
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000"), api.acked)
        assertEquals(HermesEventEnvelope("123e4567-e89b-12d3-a456-426614174000", "Title", "Body", "session-1", 2, 2.0), envelopes.single())
    }

    @Test
    fun `duplicate push fetches notifies and acknowledges only once`() = runBlocking {
        val api = FakeEventApi(Result.success(event("123e4567-e89b-12d3-a456-426614174000")))
        var notified = 0
        val gate = gate(api) { _, _ -> notified++; true }

        assertEquals(GateOutcome.NOTIFIED, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals(GateOutcome.DEDUPED, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000"), api.fetched)
        assertEquals(1, notified)
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000"), api.acked)
    }

    @Test
    fun `disabled push does not fetch`() = runBlocking {
        val api = FakeEventApi(Result.success(event("123e4567-e89b-12d3-a456-426614174000")))
        val outcome = gate(api, enabled = false) { _, _ -> error("not called") }.handlePull("123e4567-e89b-12d3-a456-426614174000")
        assertEquals(GateOutcome.DISABLED, outcome)
        assertTrue(api.fetched.isEmpty())
        assertTrue(api.acked.isEmpty())
    }

    @Test
    fun `123e4567-e89b-12d3-a456-426614174002 device does not fetch`() = runBlocking {
        val api = FakeEventApi(Result.success(event("123e4567-e89b-12d3-a456-426614174000")))
        val outcome = gate(api, deviceId = null) { _, _ -> error("not called") }.handlePull("123e4567-e89b-12d3-a456-426614174000")
        assertEquals(GateOutcome.NO_DEVICE, outcome)
        assertTrue(api.fetched.isEmpty())
    }

    @Test
    fun `revoked device fetch failure is forgotten and later retry delivers`() = runBlocking {
        val api = FakeEventApi(Result.failure(IllegalStateException("HTTP 404")))
        var notified = 0
        val gate = gate(api) { _, _ -> notified++; true }

        assertEquals(GateOutcome.FETCH_FAILURE, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        api.result = Result.success(event("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals(GateOutcome.NOTIFIED, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals(1, notified)
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000"), api.acked)
    }

    @Test
    fun `notification rejection is retryable and is not acknowledged`() = runBlocking {
        val api = FakeEventApi(Result.success(event("123e4567-e89b-12d3-a456-426614174000")))
        var allowed = false
        val gate = gate(api) { _, _ -> allowed }
        assertEquals(GateOutcome.DELIVERY_FAILURE, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(api.acked.isEmpty())
        allowed = true
        assertEquals(GateOutcome.NOTIFIED, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
    }

    @Test
    fun `ack failure retries ack without posting a second notification`() = runBlocking {
        val api = FakeEventApi(Result.success(event("123e4567-e89b-12d3-a456-426614174000")))
        api.ackResult = Result.failure(IllegalStateException("temporary ack failure"))
        var notified = 0
        val gate = gate(api) { _, _ -> notified++; true }

        assertEquals(GateOutcome.ACK_FAILURE, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        api.ackResult = Result.success(Unit)
        assertEquals(GateOutcome.ACKED, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals(1, notified)
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-426614174000"), api.acked)
    }

    @Test
    fun `delivery failure never acknowledges`() = runBlocking {
        val api = FakeEventApi(Result.success(event("123e4567-e89b-12d3-a456-426614174000")))
        val gate = gate(api) { _, _ -> false }

        assertEquals(GateOutcome.DELIVERY_FAILURE, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(api.acked.isEmpty())
    }

    @Test
    fun `event_not_found fetch maps to permanent outcome`() = runBlocking {
        val api = FakeEventApi(
            Result.failure(EventFetchException(FetchFailureKind.HTTP, 404, rpcCode = "event_not_found")),
        )
        var notified = 0
        val gate = gate(api) { _, _ -> notified++; true }

        assertEquals(GateOutcome.FETCH_PERMANENT, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals(0, notified)
        // A second call should retry fetch (deduper.forget happened):
        api.result = Result.success(event("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals(GateOutcome.NOTIFIED, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals(1, notified)
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-426614174000"), api.fetched)
    }

    @Test
    fun `already delivered event is acknowledged without re-notifying`() = runBlocking {
        val api = FakeEventApi(Result.success(event("123e4567-e89b-12d3-a456-426614174000")))
        var notified = 0
        val gate = gate(api, wasDelivered = { true }) { _, _ -> notified++; true }

        assertEquals(GateOutcome.ACKED, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals(0, notified)
        assertTrue(api.fetched.isEmpty())
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000"), api.acked)
    }

    @Test
    fun `already delivered event with failing ack yields ack failure`() = runBlocking {
        val api = FakeEventApi(Result.success(event("123e4567-e89b-12d3-a456-426614174000")))
        api.ackResult = Result.failure(IllegalStateException("ack unavailable"))
        var notified = 0
        val gate = gate(api, wasDelivered = { true }) { _, _ -> notified++; true }

        assertEquals(GateOutcome.ACK_FAILURE, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals(0, notified)
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000"), api.acked)
    }

    @Test
    fun `successful delivery records onDelivered even when ack fails`() = runBlocking {
        val api = FakeEventApi(Result.success(event("123e4567-e89b-12d3-a456-426614174000")))
        api.ackResult = Result.failure(IllegalStateException("ack unavailable"))
        val deliveredIds = mutableListOf<String>()
        val gate = gate(api, onDelivered = { deliveredIds += it }) { envelope, _ ->
            // simulate delivery success
            true
        }

        assertEquals(GateOutcome.ACK_FAILURE, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000"), deliveredIds)
        assertEquals(1, api.acked.size)

        // Fix ack and retry:
        api.ackResult = Result.success(Unit)
        assertEquals(GateOutcome.ACKED, gate.handlePull("123e4567-e89b-12d3-a456-426614174000"))
        // onDelivered not called again because deduper marks ack-pending
        assertEquals(listOf("123e4567-e89b-12d3-a456-426614174000"), deliveredIds)
    }

    private fun gate(
        api: FakeEventApi,
        enabled: Boolean = true,
        deviceId: String? = "device-1",
        wasDelivered: (String) -> Boolean = { false },
        onDelivered: (String) -> Unit = {},
        notify: (HermesEventEnvelope, Int) -> Boolean,
    ) = PushGate(PushDeps(enabled, deviceId, api, NotificationDeduper(), wasDelivered, onDelivered) { envelope, id ->
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
    var ackResult: Result<Unit> = Result.success(Unit)
    override suspend fun fetchEvent(id: String): Result<HermesEvent> { fetched += id; return result }
    override suspend fun ack(id: String): Result<Unit> { acked += id; return ackResult }
    override suspend fun pending(): Result<HermesEventsPage> = Result.success(HermesEventsPage())
}
