package dk.foss.jarvis.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object PushPayloadParser {
    fun eventIdFrom(content: ByteArray): String? = runCatching {
        Json.parseToJsonElement(content.toString(Charsets.UTF_8)).jsonObject["event_id"]
            ?.jsonPrimitive?.content?.takeIf(::isValidHermesEventId)
    }.getOrNull()
}
