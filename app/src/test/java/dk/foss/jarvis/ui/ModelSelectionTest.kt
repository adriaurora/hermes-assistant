package dk.foss.jarvis.ui

import dk.foss.jarvis.hermes.RuntimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JUnit4 tests for the pure Kotlin ModelSelection state machine.
 * JVM only — no Android dependencies.
 */
class ModelSelectionTest {

    private fun mk(): ModelSelection = ModelSelection()

    // 1. Initial state: automatic, not locked, selector not available
    @Test fun `initial state is automatic unlocked`() {
        val m = mk()
        assertEquals("Automatic", m.state.label)
        assertFalse(m.state.locked)
        assertTrue(m.state.clearSupported)
        assertFalse(m.state.selectorAvailable)
        assertNull(m.effective)
    }

    // 2. Selector availability
    @Test fun `onSelectorAvailability sets fields`() {
        val m = mk()
        m.onSelectorAvailability(modelOptions = true, lock = true, clear = true)
        assertTrue(m.state.selectorAvailable)
        assertTrue(m.state.clearSupported)
    }

    @Test fun `onSelectorAvailability false disables selector`() {
        val m = mk()
        m.onSelectorAvailability(modelOptions = false, lock = true, clear = true)
        assertFalse(m.state.selectorAvailable)
    }

    // 3. Set ack → label + locked + effective
    @Test fun `onSetAck_sets_label_locked_and_effective`() {
        val m = mk()
        m.onSelectorAvailability(true, true, true)
        val runtime = RuntimeInfo(model = "m1", route_source = "session_model_lock")
        m.onSetAck("Model A", runtime)
        assertEquals("Model A", m.state.label)
        assertTrue(m.state.locked)
        assertEquals("m1", m.effective?.model)
        assertEquals("session_model_lock", m.effective?.routeSource)
    }

    // 4. Rejection after set ack retains prior state
    @Test fun `onRejected_retains_prior_label_and_locked`() {
        val m = mk()
        m.onSelectorAvailability(true, true, true)
        val runtime = RuntimeInfo(model = "m1", route_source = "session_model_lock")
        m.onSetAck("Model A", runtime)
        assertEquals("Model A", m.state.label)
        assertTrue(m.state.locked)

        m.onRejected()
        assertEquals("Model A", m.state.label)
        assertTrue(m.state.locked)
        assertEquals("m1", m.effective?.model)
    }

    // 5. Clear ack → Automatic + unlocked + effective from runtime
    @Test fun `onClearAck_sets_Automatic_unlocked_with_effective`() {
        val m = mk()
        m.onSelectorAvailability(true, true, true)
        val runtime = RuntimeInfo(model = "m-global", route_source = "global")
        m.onClearAck(runtime)
        assertEquals("Automatic", m.state.label)
        assertFalse(m.state.locked)
        assertEquals("m-global", m.effective?.model)
        assertEquals("global", m.effective?.routeSource)
    }

    // 6. Locked + clear unsupported → canChooseAutomatic false
    @Test fun `locked_no_clear_canChooseAutomatic_is_false`() {
        val m = mk()
        m.onSelectorAvailability(true, lock = false, clear = false)
        m.onSessionInsight("locked-model", null)
        assertTrue(m.state.locked)
        assertFalse(m.state.clearSupported)
        assertFalse(m.canChooseAutomatic())
    }

    // 7. Unlocked + clear unsupported → canChooseAutomatic true
    @Test fun `unlocked_no_clear_canChooseAutomatic_is_true`() {
        val m = mk()
        m.onSelectorAvailability(true, lock = false, clear = false)
        m.onSessionInsight(null, null)
        assertFalse(m.state.locked)
        assertFalse(m.state.clearSupported)
        assertTrue(m.canChooseAutomatic())
    }

    // 8. Effective distinct from label
    @Test fun `effective_distinct_from_label`() {
        val m = mk()
        m.onSelectorAvailability(true, true, true)
        m.onSetAck("Model A", RuntimeInfo(model = "m-global", route_source = "global"))
        assertEquals("Model A", m.state.label)
        assertEquals("m-global", m.effective?.model)
        assertEquals("global", m.effective?.routeSource)
    }

    // 9. Rejection does not change effective
    @Test fun `onRejected_does_not_change_effective`() {
        val m = mk()
        m.onSelectorAvailability(true, true, true)
        val runtime = RuntimeInfo(model = "m1", route_source = "session_model_lock")
        m.onSetAck("Model A", runtime)
        assertNotNull(m.effective)
        m.onRejected()
        assertEquals("m1", m.effective?.model)
    }

    // 10. Session insight null → Automatic with default suffix
    @Test fun `sessionInsight_null_with_default_has_suffix`() {
        val m = mk()
        m.onSessionInsight(null, "hermes-agent")
        assertEquals("Automatic · hermes-agent", m.state.label)
        assertFalse(m.state.locked)
    }

    // 11. Session insight blank → Automatic with default suffix
    @Test fun `sessionInsight_blank_with_default_has_suffix`() {
        val m = mk()
        m.onSessionInsight("  ", "hermes-agent")
        assertEquals("Automatic · hermes-agent", m.state.label)
        assertFalse(m.state.locked)
    }

    // 12. Session insight non-blank → locked with that model
    @Test fun `sessionInsight_nonblank_sets_locked`() {
        val m = mk()
        m.onSessionInsight("custom-model", null)
        assertEquals("custom-model", m.state.label)
        assertTrue(m.state.locked)
    }
}