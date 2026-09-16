package dk.foss.jarvis.push

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Pruebas de TransportDecision y TransportSelector.
 */
class PushTransportTest {

    private class InMemoryChoiceStore : TransportChoiceStore {
        var choiceVal: String? = null
        var lastProbeVal: String? = null
        var lastProbeAtVal: Long? = null
        var recordedProbes: MutableList<Pair<String, Long>> = mutableListOf()

        override suspend fun choice(): String? = choiceVal
        override suspend fun lastProbe(): String? = lastProbeVal
        override suspend fun lastProbeAt(): Long? = lastProbeAtVal
        override suspend fun recordProbe(transport: String, nowMs: Long) {
            recordedProbes.add(transport to nowMs)
            lastProbeVal = transport
            lastProbeAtVal = nowMs
        }
    }

    // === TransportDecision ===

    @Test fun `decision always returns V1`() {
        assertEquals(PushTransport.V1, TransportDecision.decide("legacy", null, null, null))
        assertEquals(PushTransport.V1, TransportDecision.decide("v1", null, null, null))
        assertEquals(PushTransport.V1, TransportDecision.decide("auto", null, null, null))
        assertEquals(PushTransport.V1, TransportDecision.decide(null, "v1", 0L, 200))
        assertEquals(PushTransport.V1, TransportDecision.decide(null, null, 0L, 200))
        assertEquals(PushTransport.V1, TransportDecision.decide(null, null, 0L, 404))
        assertEquals(PushTransport.V1, TransportDecision.decide(null, null, 0L, null))
    }

    // === TransportSelector ===

    @Test fun `selector always returns V1`() = runBlocking {
        val store = InMemoryChoiceStore()
        store.choiceVal = "V1"
        var probeInvocations = 0
        val selector = TransportSelector(store) { _, _ ->
            probeInvocations++
            200
        }
        val result = selector.select("http://localhost", "key")
        assertEquals(PushTransport.V1, result)
        assertEquals(0, probeInvocations)
    }

    @Test fun `selector auto probe exitoso no llama probe`() = runBlocking {
        val store = InMemoryChoiceStore()
        store.choiceVal = "auto"
        var probeInvocations = 0
        val selector = TransportSelector(store) { _, _ ->
            probeInvocations++
            200
        }
        val result = selector.select("http://localhost", "key")
        assertEquals(PushTransport.V1, result)
        assertEquals(0, probeInvocations)
    }

    @Test fun `selector cached fresco no llama probe`() = runBlocking {
        val store = InMemoryChoiceStore()
        store.choiceVal = "auto"
        store.lastProbeVal = "v1"
        store.lastProbeAtVal = System.currentTimeMillis()
        var probeInvocations = 0
        val selector = TransportSelector(store) { _, _ ->
            probeInvocations++
            200
        }
        val result = selector.select("http://localhost", "key")
        assertEquals(PushTransport.V1, result)
        assertEquals(0, probeInvocations)
    }
}