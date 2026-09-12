package dk.foss.jarvis.data

sealed interface PendingModelIntent {
    data class Set(val modelId: String, val label: String) : PendingModelIntent
    data object Clear : PendingModelIntent
}
