package dk.foss.jarvis.ui

import dk.foss.jarvis.data.PendingModelIntent
import org.junit.Assert.*
import org.junit.Test

class PendingModelLabelTest {
    @Test fun `restored selection stays visible before first session`() {
        assertEquals("Selected model", pendingModelLabel(PendingModelIntent.Set("id", "Selected model")))
        assertEquals("Automatic", pendingModelLabel(PendingModelIntent.Clear))
        assertNull(pendingModelLabel(null))
    }
}
