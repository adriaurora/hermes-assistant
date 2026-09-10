package dk.foss.jarvis.ui

import dk.foss.jarvis.hermes.RuntimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for model state isolation when the active conversation changes. */
class ModelSelectionStateRegressionTest {
    @Test
    fun `locked model then new empty conversation resets to automatic`() {
        val selection = ModelSelection()
        selection.onSetAck("qwen3.6", RuntimeInfo(model = "qwen3.6", route_source = "session_model_lock"))

        selection.reset()

        assertEquals("Automatic", selection.state.label)
        assertFalse(selection.state.locked)
        assertNull(selection.effective)
    }

    @Test
    fun `locked model then deleted or empty conversation has clean state`() {
        val selection = ModelSelection()
        selection.onSetAck("mimo-v2.5", RuntimeInfo(model = "mimo-v2.5", route_source = "session_model_lock"))

        selection.reset()

        assertEquals(ModelSelectionState(), selection.state)
        assertNull(selection.effective)
    }

    @Test
    fun `switching A locked to B empty then back to A applies server state`() {
        val selection = ModelSelection()
        selection.onSessionInsight("qwen3.6", "mimo-v2.5")
        assertTrue(selection.state.locked)
        assertEquals("qwen3.6", selection.state.label)

        selection.reset()
        selection.onSessionInsight(null, "mimo-v2.5")
        assertEquals("Automatic · mimo-v2.5", selection.state.label)
        assertFalse(selection.state.locked)
        assertNull(selection.effective)

        selection.reset()
        selection.onSessionInsight("qwen3.6", "mimo-v2.5")
        assertEquals("qwen3.6", selection.state.label)
        assertTrue(selection.state.locked)
    }

    @Test
    fun `session without id reset never inherits previous label or route`() {
        val selection = ModelSelection()
        selection.onSetAck("mimo-v2.5", RuntimeInfo(model = "mimo-v2.5", route_source = "global"))
        assertEquals("mimo-v2.5", selection.state.label)

        // Mirrors the sid == null branch in ChatViewModel.syncLabelWithServer().
        selection.reset()

        assertEquals("Automatic", selection.state.label)
        assertFalse(selection.state.locked)
        assertNull(selection.effective)
    }

    @Test
    fun `reset preserves selector availability and clear support`() {
        val selection = ModelSelection()
        selection.onSelectorAvailability(modelOptions = true, lock = true, clear = true)
        selection.onSetAck("qwen3.6", RuntimeInfo(model = "qwen3.6", route_source = "session_model_lock"))

        selection.reset()

        assertEquals("Automatic", selection.state.label)
        assertFalse(selection.state.locked)
        assertNull(selection.effective)
        assertTrue(selection.state.selectorAvailable)
        assertTrue(selection.state.clearSupported)
    }

    @Test
    fun `sid null sync reset keeps the picker available`() {
        val selection = ModelSelection()
        selection.onSelectorAvailability(modelOptions = true, lock = true, clear = true)

        selection.reset()

        assertTrue(selection.state.selectorAvailable)
        assertEquals("Automatic", selection.state.label)
    }

    @Test
    fun `reset after unavailable capabilities keeps them unavailable`() {
        val selection = ModelSelection()
        selection.onSelectorAvailability(modelOptions = false, lock = false, clear = false)

        selection.reset()

        assertFalse(selection.state.selectorAvailable)
    }
}
