package dk.foss.jarvis.ui

import androidx.compose.runtime.mutableStateOf
import dk.foss.jarvis.hermes.RuntimeInfo

/**
 * Pure Kotlin state machine for per-session model selection.
 * Compose-observable state; androidx.compose.runtime is JVM-safe and fully
 * JVM-testable.
 *
 * Semantics:
 * - label: display string ("Automatic" or the server-confirmed model)
 * - locked: true when the server confirmed a model lock
 * - clearSupported: whether session_model_clear capability exists
 * - selectorAvailable: whether model_options && session_model_lock are true
 * - effective: the actual route (model + route_source) from runtime info
 */
data class ModelSelectionState(
    val label: String = "Automatic",
    val locked: Boolean = false,
    val clearSupported: Boolean = true,
    val selectorAvailable: Boolean = false,
)

/** The actual model and route source the server is using. */
data class EffectiveRoute(val model: String?, val routeSource: String?) {
    companion object {
        fun fromRuntime(r: RuntimeInfo?): EffectiveRoute? =
            r?.let { EffectiveRoute(it.model, it.route_source) }
    }
}

class ModelSelection {
    private var _state = mutableStateOf(ModelSelectionState())

    /** Compose-observable state so UI readers (e.g. ChatScreen) recompose when it changes. */
    var state: ModelSelectionState
        get() = _state.value
        private set(value) { _state.value = value }
    var effective: EffectiveRoute? = null

    /** Called after refreshModel() resolves caps. */
    fun onSelectorAvailability(modelOptions: Boolean, lock: Boolean, clear: Boolean) {
        state = state.copy(
            selectorAvailable = modelOptions && lock,
            clearSupported = clear,
        )
    }

    /** Called after GET /api/sessions/{id} returns the session object. */
    fun onSessionInsight(sessionModel: String?, defaultModel: String?) {
        if (sessionModel.isNullOrBlank()) {
            state = state.copy(label = defaultModel?.let { "Automatic · $it" } ?: "Automatic", locked = false)
        } else {
            state = state.copy(label = sessionModel, locked = true)
        }
    }

    /** Called after a successful 200 from setSessionModel. */
    fun onSetAck(modelLabel: String, runtime: RuntimeInfo?) {
        state = state.copy(label = modelLabel, locked = true)
        effective = EffectiveRoute.fromRuntime(runtime)
    }

    /** Called after a successful 200 from clearSessionModel. */
    fun onClearAck(runtime: RuntimeInfo?) {
        state = state.copy(label = "Automatic", locked = false)
        effective = EffectiveRoute.fromRuntime(runtime)
    }

    /** Called on server rejection / 4xx/5xx — retains last confirmed state. */
    fun onRejected() {
        // No state change
    }

    /** Reset per-conversation model state (label, lock, effective route) while
     *  preserving capability-derived selector availability, which is origin-scoped. */
    fun reset() {
        state = state.copy(label = "Automatic", locked = false)
        effective = null
    }

    /**
     * Can the user choose Automatic? False only when locked AND server
     * cannot clear it. If unlocked, Automatic is always allowed.
     */
    fun canChooseAutomatic(): Boolean = !state.locked || state.clearSupported
}
