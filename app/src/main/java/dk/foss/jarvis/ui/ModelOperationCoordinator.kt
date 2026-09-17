package dk.foss.jarvis.ui

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Deterministic ordering/obsolescence primitive used by model operations. */
internal class ModelOperationCoordinator {
    companion object { val shared = ModelOperationCoordinator() }
    data class Context(val conversationId: String, val origin: String?, val sessionId: String?, val generation: Long)
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val generations = ConcurrentHashMap<String, Long>()

    fun next(conversationId: String, origin: String?, sessionId: String?) =
        Context(conversationId, origin, sessionId, generations.merge(conversationId, 1L, Long::plus)!!)
    fun withOrigin(context: Context, origin: String) = context.copy(origin = origin)

    fun isCurrent(context: Context, current: () -> Boolean): Boolean =
        generations[context.conversationId] == context.generation && current()

    suspend inline fun <T> run(context: Context, noinline current: () -> Boolean, block: suspend () -> T): T? =
        mutexes.getOrPut(context.conversationId) { Mutex() }.withLock {
            if (!isCurrent(context, current)) null
            else block().takeIf { isCurrent(context, current) }
        }
}
