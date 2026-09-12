package dk.foss.jarvis.hermes

/** Collapse a first user message into a readable, length-capped session title. */
fun sessionTitleFrom(firstUserText: String?): String {
    val cleaned = firstUserText?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    return cleaned.take(60).ifBlank { "Conversation" }
}

/** Create a session, retrying confirmed duplicate titles once with a deterministic suffix. */
suspend fun createSessionForFirstTurn(client: HermesClient, baseTitle: String, uniqueSuffix: String): Result<String> {
    val first = client.createSession(baseTitle)
    if (first.isSuccess) return first
    val error = first.exceptionOrNull() as? HermesHttpError
    // Confirmed title collisions use session_exists or invalid_title; without an RPC code, HTTP 409 is treated as a collision.
    val isTitleCollision = when {
        error?.rpcCode == "session_exists" -> true
        error?.rpcCode == "invalid_title" -> true
        error?.rpcCode == null && error?.code == 409 -> true
        else -> false
    }
    if (!isTitleCollision) return first
    return client.createSession("$baseTitle · ${uniqueSuffix.take(8)}")
}
