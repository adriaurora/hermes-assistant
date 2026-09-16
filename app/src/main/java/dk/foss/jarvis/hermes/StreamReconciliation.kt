package dk.foss.jarvis.hermes

/** Recover only an answer following the exact transcript sent by this client.
 * A previous turn's answer (or another client's turn) is not proof of success.
 * Truncated or divergent history stays uncertain; never replay the request.
 */
internal fun reconciledAnswer(expected: List<ChatMessage>, remote: List<ChatMessage>): String? {
    val history = remote.filter { it.role in setOf("user", "assistant") && it.content.isNotBlank() }
    val prefix = expected.filter { it.role in setOf("user", "assistant") && it.content.isNotBlank() }
    if (prefix.lastOrNull()?.role != "user" || history.size <= prefix.size) return null
    if (history.take(prefix.size) != prefix) return null
    val remainder = history.drop(prefix.size)
    if (remainder.any { it.role == "user" }) return null
    return remainder.lastOrNull { it.role == "assistant" }?.content
}
