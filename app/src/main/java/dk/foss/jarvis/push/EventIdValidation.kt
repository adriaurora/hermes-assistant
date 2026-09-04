package dk.foss.jarvis.push

import java.util.UUID

/** Hermes event IDs are canonical UUIDs; reject ambiguous path/input values. */
fun isValidHermesEventId(value: String): Boolean =
    (value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) &&
        runCatching { UUID.fromString(value) }.isSuccess) ||
        value.matches(Regex("[0-9a-f]{32}"))
