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
        var lastProbeVal: PushTransport? = null
        var lastProbeAtVal: Long? = null
        var recordedProbes: MutableList<Pair<PushTransport, Long>> = mutableListOf()

        override suspend fun choice(): String? = choiceVal
        override suspend fun lastProbe(): PushTransport? = lastProbeVal
        override suspend fun lastProbeAt(): Long? = lastProbeAtVal
        override suspend fun recordProbe(transport: PushTransport, nowMs: Long) {
            recordedProbes.add(transport to nowMs)
            lastProbeVal = transport
            lastProbeAtVal = nowMs
        }
    }

    // === TransportDecision ===

    @Test fun `decision choice "legacy" → LEGACY`() {
        assertEquals(PushTransport.LEGACY, TransportDecision.decide("legacy", null, null, null))
    }

    @Test fun `decision choice "v1" → V1`() {
        assertEquals(PushTransport.V1, TransportDecision.decide("v1", null, null, null))
    }

    @Test fun `decision choice "V1" mayúsculas → V1`() {
        assertEquals(PushTransport.V1, TransportDecision.decide("V1", null, null, null))
    }

    @Test fun `decision "auto"+cached fresco → cached`() {
        // age 0 < TTL → cached gana
        assertEquals(PushTransport.V1, TransportDecision.decide("auto", PushTransport.V1, 0L, null))
        assertEquals(PushTransport.LEGACY, TransportDecision.decide("auto", PushTransport.LEGACY, 0L, null))
    }

    @Test fun `decision "auto"+cache caducada+probe 200 → V1`() {
        assertEquals(PushTransport.V1, TransportDecision.decide("auto", null, TransportDecision.PROBE_TTL_MS + 1000, 200))
    }

    @Test fun `decision "auto"+cache caducada+probe 401 → V1`() {
        assertEquals(PushTransport.V1, TransportDecision.decide("auto", null, TransportDecision.PROBE_TTL_MS + 1000, 401))
    }

    @Test fun `decision "auto"+cache caducada+probe 404 → LEGACY`() {
        assertEquals(PushTransport.LEGACY, TransportDecision.decide("auto", null, TransportDecision.PROBE_TTL_MS + 1000, 404))
    }

    @Test fun `decision "auto"+cache caducada+probe 503 → LEGACY`() {
        assertEquals(PushTransport.LEGACY, TransportDecision.decide("auto", null, TransportDecision.PROBE_TTL_MS + 1000, 503))
    }

    @Test fun `decision "auto"+cache caducada+probe null → LEGACY`() {
        assertEquals(PushTransport.LEGACY, TransportDecision.decide("auto", null, TransportDecision.PROBE_TTL_MS + 1000, null))
    }

    @Test fun `decision cached fresco gana al probe`() {
        // cached age 0 (fresco) + probe 404 → cached (V1) gana
        assertEquals(PushTransport.V1, TransportDecision.decide("auto", PushTransport.V1, 0L, 404))
    }

    // === TransportSelector ===

    @Test fun `selector choice explicit V1 no llama probe`() = runBlocking {
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

    @Test fun `selector choice explicit legacy no llama probe`() = runBlocking {
        val store = InMemoryChoiceStore()
        store.choiceVal = "legacy"
        var probeInvocations = 0
        val selector = TransportSelector(store) { _, _ ->
            probeInvocations++
            200
        }
        val result = selector.select("http://localhost", "key")
        assertEquals(PushTransport.LEGACY, result)
        assertEquals(0, probeInvocations)
    }

    @Test fun `selector auto probe exitoso graba recordProbe`() = runBlocking {
        val store = InMemoryChoiceStore()
        store.choiceVal = "auto"
        var probeInvocations = 0
        val selector = TransportSelector(store) { _, _ ->
            probeInvocations++
            200
        }
        val result = selector.select("http://localhost", "key")
        assertEquals(PushTransport.V1, result)
        assertEquals(1, probeInvocations)
        assertEquals(1, store.recordedProbes.size)
        assertEquals(PushTransport.V1, store.recordedProbes[0].first)
    }

    @Test fun `selector probe exception → LEGACY y NO graba`() = runBlocking {
        val store = InMemoryChoiceStore()
        store.choiceVal = "auto"
        var probeInvocations = 0
        val selector = TransportSelector(store) { _, _ ->
            probeInvocations++
            throw RuntimeException("network error")
        }
        val result = selector.select("http://localhost", "key")
        assertEquals(PushTransport.LEGACY, result)
        assertEquals(1, probeInvocations)
        assertEquals(0, store.recordedProbes.size)
    }

    @Test fun `selector cached fresco no llama probe`() = runBlocking {
        val store = InMemoryChoiceStore()
        store.choiceVal = "auto"
        store.lastProbeVal = PushTransport.V1
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
