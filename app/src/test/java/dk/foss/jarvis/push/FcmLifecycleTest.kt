package dk.foss.jarvis.push

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FcmLifecycleTest {

    @Test
    fun `concurrent withLockReturning blocks serialize`() = runBlocking {
        val inFlight = AtomicInteger(0)
        val overlaps = AtomicInteger(0)

        val jobs = (1..2).map {
            launch {
                FcmLifecycle.withLockReturning {
                    val current = inFlight.incrementAndGet()
                    if (current > 1) overlaps.incrementAndGet()
                    withTimeout(2000) { kotlinx.coroutines.delay(100) }
                    inFlight.decrementAndGet()
                    true
                }
            }
        }
        jobs.forEach { it.join() }

        assertEquals(0, overlaps.get())
    }
}