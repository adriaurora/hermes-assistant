package dk.foss.jarvis.ui

import dk.foss.jarvis.data.PendingModelIntent

internal fun pendingModelLabel(intent: PendingModelIntent?): String? = when (intent) {
    is PendingModelIntent.Set -> intent.label
    PendingModelIntent.Clear -> "Automatic"
    null -> null
}
