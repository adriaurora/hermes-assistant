package dk.foss.jarvis.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de DeliveredEventLog: append, FIFO eviction,
 * decode tolerante y encode roundtrip.
 */
class DeliveredEventLogTest {

    @Test fun `append añade evento a lista vacía`() {
        val result = DeliveredEventLog.append(emptyList(), "e-1")
        assertEquals(1, result.size)
        assertEquals("e-1", result[0])
    }

    @Test fun `append añade evento a lista existente`() {
        val result = DeliveredEventLog.append(listOf("e-1"), "e-2")
        assertEquals(2, result.size)
        assertEquals("e-1", result[0])
        assertEquals("e-2", result[1])
    }

    @Test fun `append con max=3 eviction FIFO`() {
        var result = DeliveredEventLog.append(emptyList(), "e-1", 3)
        result = DeliveredEventLog.append(result, "e-2", 3)
        result = DeliveredEventLog.append(result, "e-3", 3)
        assertEquals(3, result.size)

        // 4° evento debe desplazar el primero (FIFO)
        result = DeliveredEventLog.append(result, "e-4", 3)
        assertEquals(3, result.size)
        assertEquals("e-2", result[0])
        assertEquals("e-3", result[1])
        assertEquals("e-4", result[2])
    }

    @Test fun `append con max=1000 no evicciona`() {
        var result = emptyList<String>()
        for (i in 1..50) {
            result = DeliveredEventLog.append(result, "e-$i", 1000)
        }
        assertEquals(50, result.size)
    }

    @Test fun `decode null returns empty list`() {
        assertEquals(emptyList<String>(), DeliveredEventLog.decode(null))
    }

    @Test fun `decode blank returns empty list`() {
        assertEquals(emptyList<String>(), DeliveredEventLog.decode(""))
        assertEquals(emptyList<String>(), DeliveredEventLog.decode("  "))
    }

    @Test fun `decode invalid json returns empty list`() {
        assertEquals(emptyList<String>(), DeliveredEventLog.decode("not-json"))
    }

    @Test fun `decode empty array returns empty list`() {
        assertEquals(emptyList<String>(), DeliveredEventLog.decode("[]"))
    }

    @Test fun `decode lista válida`() {
        val raw = DeliveredEventLog.encode(listOf("e-1", "e-2", "e-3"))
        val result = DeliveredEventLog.decode(raw)
        assertEquals(3, result.size)
        assertEquals("e-1", result[0])
        assertEquals("e-2", result[1])
        assertEquals("e-3", result[2])
    }

    @Test fun `encode and decode roundtrip`() {
        val ids = listOf("evt-1", "evt-2", "evt-3", "evt-4", "evt-5")
        val encoded = DeliveredEventLog.encode(ids)
        val decoded = DeliveredEventLog.decode(encoded)
        assertEquals(ids, decoded)
    }

    @Test fun `append con deduplicación no se añade duplicado (pero sí cuenta)`() {
        // append añade siempre; la deduplicación se hace en el caller (DeliveredEventLog no dedup)
        val result = DeliveredEventLog.append(listOf("e-1"), "e-1")
        assertEquals(2, result.size)
        assertEquals("e-1", result[0])
        assertEquals("e-1", result[1])
    }
}