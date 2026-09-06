package dk.foss.jarvis.push

/** FCM data-only contract: notification payloads are intentionally handled elsewhere/ignored. */
object FcmPayloadParser {
    fun eventId(data: Map<String, String>): String? =
        data["event_id"]?.trim()?.takeIf { isValidHermesEventId(it) &&
            (data["protocol_version"]?.trim().let { version -> version == null || version.isEmpty() || version == "1" }) }
}
