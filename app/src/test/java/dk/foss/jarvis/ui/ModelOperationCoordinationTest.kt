package dk.foss.jarvis.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelOperationCoordinationTest {
    @Test fun `A then B drops A response and preserves B ordering`() = runBlocking {
      coroutineScope {
        val c = ModelOperationCoordinator(); var current = 0L; val applied = mutableListOf<String>()
        val a = c.next("conversation", "origin", "sid"); current = a.generation
        val first = async { c.run(a, { current == a.generation }) { delay(10); "A" } }
        val b = c.next("conversation", "origin", "sid"); current = b.generation
        val second = async { c.run(b, { current == b.generation }) { "B" } }
        first.await()?.let(applied::add); second.await()?.let(applied::add)
        assertEquals(listOf("B"), applied)
      }
    }

    @Test fun `late ACK cannot cross conversation or session binding`() = runBlocking {
      coroutineScope {
        val c = ModelOperationCoordinator(); val x = c.next("a", "o", "s1")
        var active = true
        val result = async { c.run(x, { active }) { delay(5); "ACK-A" } }
        active = false
        assertNull(result.await())
      }
    }

    @Test fun `voice and text use one serialized coordinator`() = runBlocking {
      coroutineScope {
        val c = ModelOperationCoordinator(); val order = mutableListOf<String>()
        val a = c.next("a", "o", "s"); val b = c.next("a", "o", "s")
        async { c.run(a, { false }) { order += "voice" } }.await()
        async { c.run(b, { true }) { order += "text" } }.await()
        assertEquals(listOf("text"), order)
      }
    }
}
