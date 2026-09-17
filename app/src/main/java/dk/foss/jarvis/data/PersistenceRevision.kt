package dk.foss.jarvis.data

import java.util.concurrent.atomic.AtomicLong

/** A completed write acknowledges only the snapshot it actually wrote. */
internal class PersistenceRevision {
    private val revision = AtomicLong()
    private val persisted = AtomicLong()
    val current: Long get() = revision.get()
    val isDirty: Boolean get() = current != persisted.get()
    fun changed() { revision.incrementAndGet() }
    fun saved(snapshot: Long) { persisted.accumulateAndGet(snapshot, ::maxOf) }
}
