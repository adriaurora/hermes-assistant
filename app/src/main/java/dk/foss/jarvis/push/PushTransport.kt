package dk.foss.jarvis.push

/** Wire protocol version: V1 is the only supported transport. */
object PushTransport {
    const val V1 = "v1"
}

enum class PushTransportChoice(val raw: String) {
    V1("v1")
}

interface TransportChoiceStore {
    suspend fun choice(): String?
    suspend fun lastProbe(): String?
    suspend fun lastProbeAt(): Long?
    suspend fun recordProbe(transport: String, nowMs: Long)
}

object TransportDecision {
    fun decide(choice: String?, cached: String?, cachedAgeMs: Long?, probeStatus: Int?, nowMs: Long = 0L): String = PushTransport.V1
}

class TransportSelector private constructor() {
    constructor(store: TransportChoiceStore, probe: suspend (String, String) -> Int?) : this()

    suspend fun select(baseUrl: String, apiKey: String): String = PushTransport.V1
}