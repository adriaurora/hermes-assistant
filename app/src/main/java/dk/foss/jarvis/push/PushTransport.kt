package dk.foss.jarvis.push

enum class PushTransport { LEGACY, V1 }
enum class PushTransportChoice(val raw: String) { AUTO("auto"), LEGACY("legacy"), V1("v1") }

interface TransportChoiceStore {
    suspend fun choice(): String?
    suspend fun lastProbe(): PushTransport?
    suspend fun lastProbeAt(): Long?
    suspend fun recordProbe(transport: PushTransport, nowMs: Long)
}

object TransportDecision {
    const val PROBE_TTL_MS = 5 * 60 * 1000L
    fun decide(choice: String?, cached: PushTransport?, cachedAgeMs: Long?, probeStatus: Int?, nowMs: Long = 0L): PushTransport {
        when (choice?.lowercase()) { "legacy" -> return PushTransport.LEGACY; "v1" -> return PushTransport.V1 }
        if (cached != null && cachedAgeMs != null && cachedAgeMs < PROBE_TTL_MS) return cached
        return if (probeStatus == 200 || probeStatus == 401) PushTransport.V1 else PushTransport.LEGACY
    }
}

class TransportSelector(private val store: TransportChoiceStore, private val probe: suspend (String, String) -> Int?) {
    suspend fun select(baseUrl: String, apiKey: String): PushTransport {
        val choice = store.choice()
        if (choice.equals("legacy", true) || choice.equals("v1", true)) return TransportDecision.decide(choice, null, null, null)
        val now = System.currentTimeMillis()
        val cachedAt = store.lastProbeAt()
        val age = cachedAt?.let { now - it }
        val cached = store.lastProbe()
        if (cached != null && age != null && age < TransportDecision.PROBE_TTL_MS) return cached
        return try {
            val status = probe(baseUrl, apiKey)
            val selected = TransportDecision.decide(choice, null, null, status)
            store.recordProbe(selected, now)
            selected
        } catch (_: Throwable) { PushTransport.LEGACY }
    }
}
