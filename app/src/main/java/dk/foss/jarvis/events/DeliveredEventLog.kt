package dk.foss.jarvis.events

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object DeliveredEventLog {
    private val json = Json
    fun append(existing: List<String>, id: String, max: Int): List<String> {
        val result = (existing + id).toMutableList()
        while (result.size > max) result.removeAt(0)
        return result
    }
    fun append(existing: List<String>, id: String): List<String> = append(existing, id, 128)
    fun decode(raw: String?): List<String> = runCatching {
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString(ListSerializer(String.serializer()), raw)
    }.getOrDefault(emptyList())
    fun encode(ids: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), ids)
}
