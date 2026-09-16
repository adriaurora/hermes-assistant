package dk.foss.jarvis.ui

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Deterministic ordering/obsolescence primitive used by model operations. */
internal class ModelOperationCoordinator {
    data class Context(val conversationId: String, val origin: String?, val sessionId: String?, val generation: Long)
    private val mutex = Mutex()
    private var generation = 0L

    fun next(conversationId: String, origin: String?, sessionId: String?) =
        Context(conversationId, origin, sessionId, ++generation)

    suspend fun <T> run(context: Context, current: () -> Boolean, block: suspend () -> T): T? =
        mutex.withLock { if (context.generation != generation || !current()) null else block().takeIf { current() && context.generation == generation } }
}
