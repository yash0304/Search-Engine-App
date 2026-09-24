package com.sarvam.voiceassistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the conversation on the phone for a day, then lets it go — like Snapchat.
 *
 * Every message disappears [LIFETIME_MS] after it was sent, and so does the text of every
 * document shared into the chat. Nothing is kept longer "just in case": a conversation you
 * had yesterday is gone, which is the point.
 *
 * Stored as JSON in the app's private storage, which other apps cannot read. The manifest
 * already disables backup, so it never leaves the phone either.
 */
class ConversationStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class SavedDocument(val name: String, val text: String, val addedAt: Long)

    data class Snapshot(
        val messages: List<Message> = emptyList(),
        val documents: List<SavedDocument> = emptyList(),
    ) {
        /** When the oldest message goes, or null when there is nothing to lose. */
        fun nextExpiry(): Long? = messages.minOfOrNull { it.sentAt }?.plus(LIFETIME_MS)
    }

    /** What is saved, minus anything past its 24 hours. Never throws: a bad file is no history. */
    fun load(): Snapshot {
        if (!file.exists()) return Snapshot()
        return runCatching { prune(decode(file.readText())) }.getOrElse { Snapshot() }
    }

    /** Writes to a temporary file first, so a crash mid-write cannot corrupt the history. */
    @Synchronized
    fun save(snapshot: Snapshot) {
        val pruned = prune(snapshot)
        if (pruned.messages.isEmpty() && pruned.documents.isEmpty()) {
            clear()
            return
        }
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(encode(pruned))
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    fun prune(snapshot: Snapshot): Snapshot {
        val cutoff = clock() - LIFETIME_MS
        return Snapshot(
            messages = snapshot.messages.filter { it.sentAt > cutoff },
            documents = snapshot.documents.filter { it.addedAt > cutoff },
        )
    }

    companion object {
        const val LIFETIME_MS = 24 * 60 * 60 * 1000L

        private const val VERSION = 1

        fun encode(snapshot: Snapshot): String {
            val messages = JSONArray()
            snapshot.messages.forEach { message ->
                messages.put(
                    JSONObject()
                        .put("role", message.role.name)
                        .put("text", message.text)
                        .put("language", message.languageCode ?: JSONObject.NULL)
                        .put("sentAt", message.sentAt),
                )
            }
            val documents = JSONArray()
            snapshot.documents.forEach { document ->
                documents.put(
                    JSONObject()
                        .put("name", document.name)
                        .put("text", document.text)
                        .put("addedAt", document.addedAt),
                )
            }
            return JSONObject()
                .put("version", VERSION)
                .put("messages", messages)
                .put("documents", documents)
                .toString()
        }

        /** Skips any entry it cannot read rather than discarding the whole history. */
        fun decode(json: String): Snapshot {
            val root = JSONObject(json)
            val messages = buildList {
                val array = root.optJSONArray("messages") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val role = runCatching { Role.valueOf(item.optString("role")) }.getOrNull() ?: continue
                    val text = item.stringOrNull("text") ?: continue
                    val sentAt = item.optLong("sentAt", 0L).takeIf { it > 0 } ?: continue
                    add(Message(role, text, item.stringOrNull("language"), sentAt))
                }
            }
            val documents = buildList {
                val array = root.optJSONArray("documents") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val name = item.stringOrNull("name") ?: continue
                    val text = item.stringOrNull("text") ?: continue
                    val addedAt = item.optLong("addedAt", 0L).takeIf { it > 0 } ?: continue
                    add(SavedDocument(name, text, addedAt))
                }
            }
            return Snapshot(messages, documents)
        }

        /** "23h", "5h", "40 min", "1 min": how long until something disappears. */
        fun describeRemaining(millis: Long): String {
            val minutes = (millis / 60_000).coerceAtLeast(1)
            return if (minutes >= 60) "${minutes / 60}h" else "$minutes min"
        }
    }
}
