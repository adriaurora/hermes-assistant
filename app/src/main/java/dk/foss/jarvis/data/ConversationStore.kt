package dk.foss.jarvis.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Persists conversations as one JSON file each under filesDir/conversations/. */
class ConversationStore {

    internal val dir: File
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "conversations").apply { mkdirs() })

    internal constructor(dir: File) {
        this.dir = dir
        dir.mkdirs()
    }

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        runCatching {
            // Write to a temp file then atomically rename, so concurrent/torn writes
            // can't corrupt the JSON.
            val target = File(dir, "${conversation.id}.json")
            val tmp = File(dir, "${conversation.id}.json.tmp")
            tmp.writeText(json.encodeToString(Conversation.serializer(), conversation))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText()); tmp.delete()
            }
        }
        Unit
    }

    suspend fun load(id: String): Conversation? = withContext(Dispatchers.IO) {
        val f = File(dir, "$id.json")
        if (!f.exists()) return@withContext null
        decodeConversation(f.readText())
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        runCatching { File(dir, "$id.json").delete() }
        Unit
    }

    /** All conversations as lightweight metadata, newest first. */
    suspend fun list(): List<ConversationMeta> = withContext(Dispatchers.IO) {
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f ->
                runCatching {
                    val c = decodeConversation(f.readText()) ?: return@runCatching null
                    ConversationMeta(c.id, c.title, c.updatedAt, c.messages.size, c.sessionId, c.transport, c.origin)
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    /** Atomically rebind one already authenticated conversation. */
    suspend fun rebindOrigin(id: String, newOrigin: String) = withContext(Dispatchers.IO) {
        load(id)?.let { save(it.copy(origin = newOrigin, updatedAt = System.currentTimeMillis())) }
    }

    private fun decodeConversation(raw: String): Conversation? = runCatching {
        try { json.decodeFromString(Conversation.serializer(), raw) }
        catch (_: Exception) {
            val obj = Json.decodeFromString(JsonObject.serializer(), raw).toMutableMap()
            val stored = obj["transport"]?.jsonPrimitive?.content
            if (stored == null || stored == "SESSIONS") throw IllegalArgumentException("invalid conversation")
            obj.remove("transport")
            json.decodeFromString(Conversation.serializer(), JsonObject(obj).toString())
        }
    }.getOrNull()
}
