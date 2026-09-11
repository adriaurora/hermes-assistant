package dk.foss.jarvis.hermes

/** Collapse a first user message into a readable, length-capped session title. */
fun sessionTitleFrom(firstUserText: String?): String {
    val cleaned = firstUserText?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    return cleaned.take(60).ifBlank { "Conversation" }
}

/** Create a session, retrying duplicate titles once with a deterministic suffix. */
suspend fun createSessionForFirstTurn(client: HermesClient, baseTitle: String, uniqueSuffix: String): Result<String> {
    val first = client.createSession(baseTitle)
    if (first.isSuccess) return first
    val error = first.exceptionOrNull() as? HermesHttpError
    // Only session_exists is a confirmed title collision; without an RPC code, 409 is the legacy heuristic.
    val isTitleCollision = when {
        error?.rpcCode != null -> error.rpcCode == "session_exists"
        else -> error?.code == 409
    }
    if (!isTitleCollision) return first
    return client.createSession("$baseTitle · ${uniqueSuffix.take(8)}")
}
