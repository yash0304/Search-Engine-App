package com.sarvam.voiceassistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps chats on the phone for a day, then lets them go — like Snapchat.
 *
 * There are separate chats rather than one running conversation: everything used to go into
 * a single chat, so a photo shared in the morning was still in context for an unrelated
 * question in the afternoon. Each chat has its own messages, documents and model memory.
 *
 * Every message disappears [LIFETIME_MS] after it was sent, and so does the text of every
 * document shared into a chat; a chat goes when its last message does. Within those 24
 * hours nothing is merged or dropped.
 *
 * Stored as JSON in the app's private storage, which other apps cannot read. The manifest
 * disables backup, so it never leaves the phone either.
 */
class ConversationStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class SavedDocument(val name: String, val text: String, val addedAt: Long)

    data class Chat(
        val id: String,
        val title: String,
        val createdAt: Long,
        val messages: List<Message> = emptyList(),
        val documents: List<SavedDocument> = emptyList(),
        /** True once the model has written this chat's one-line title; it is asked only once. */
        val autoTitled: Boolean = false,
    ) {
        val lastActivity: Long get() = messages.maxOfOrNull { it.sentAt } ?: createdAt

        /** When the oldest message goes, or null when there is nothing to lose. */
        fun nextExpiry(): Long? = messages.minOfOrNull { it.sentAt }?.plus(LIFETIME_MS)
    }

    /** Saved chats, newest activity first, minus anything past its 24 hours. Never throws. */
    fun load(): List<Chat> {
        if (!file.exists()) return emptyList()
        return runCatching { prune(decode(file.readText())) }.getOrElse { emptyList() }
    }

    /** Writes to a temporary file first, so a crash mid-write cannot corrupt the history. */
    @Synchronized
    fun save(chats: List<Chat>) {
        val kept = prune(chats)
        if (kept.isEmpty()) {
            clear()
            return
        }
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(encode(kept))
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    /** Drops expired messages and documents, then any chat left with no messages. */
    fun prune(chats: List<Chat>): List<Chat> {
        val cutoff = clock() - LIFETIME_MS
        return chats
            .map { chat ->
                chat.copy(
                    messages = chat.messages.filter { it.sentAt > cutoff },
                    documents = chat.documents.filter { it.addedAt > cutoff },
                )
            }
            .filter { it.messages.isNotEmpty() }
            .sortedByDescending { it.lastActivity }
    }

    companion object {
        const val LIFETIME_MS = 24 * 60 * 60 * 1000L

        private const val VERSION = 2
        private const val TITLE_LENGTH = 48

        /** A chat is named after how it started: the first question, or the document read. */
        fun titleFor(firstUserText: String?, documentName: String?): String = when {
            documentName != null -> "Read: $documentName"
            !firstUserText.isNullOrBlank() -> {
                val text = firstUserText.trim().replace(Regex("\\s+"), " ")
                if (text.length <= TITLE_LENGTH) text else text.take(TITLE_LENGTH - 1).trimEnd() + "…"
            }
            else -> "New chat"
        }

        fun encode(chats: List<Chat>): String {
            val array = JSONArray()
            chats.forEach { chat ->
                array.put(
                    JSONObject()
                        .put("id", chat.id)
                        .put("title", chat.title)
                        .put("createdAt", chat.createdAt)
                        .put("autoTitled", chat.autoTitled)
                        .put("messages", encodeMessages(chat.messages))
                        .put("documents", encodeDocuments(chat.documents)),
                )
            }
            return JSONObject().put("version", VERSION).put("chats", array).toString()
        }

        /**
         * Reads the current format, and the single-conversation format that came before it,
         * which becomes one chat. Skips any entry it cannot read rather than losing them all.
         */
        fun decode(json: String): List<Chat> {
            val root = JSONObject(json)
            val chats = root.optJSONArray("chats")
            if (chats == null) {
                val messages = decodeMessages(root.optJSONArray("messages"))
                if (messages.isEmpty()) return emptyList()
                val documents = decodeDocuments(root.optJSONArray("documents"))
                val title = titleFor(messages.firstOrNull { it.role == Role.USER }?.text, documents.firstOrNull()?.name)
                return listOf(Chat("migrated", title, messages.first().sentAt, messages, documents))
            }
            return buildList {
                for (i in 0 until chats.length()) {
                    val item = chats.optJSONObject(i) ?: continue
                    val id = item.stringOrNull("id") ?: continue
                    val messages = decodeMessages(item.optJSONArray("messages"))
                    add(
                        Chat(
                            id = id,
                            title = item.stringOrNull("title") ?: "Chat",
                            createdAt = item.optLong("createdAt", messages.firstOrNull()?.sentAt ?: 0L),
                            messages = messages,
                            documents = decodeDocuments(item.optJSONArray("documents")),
                            autoTitled = item.optBoolean("autoTitled", false),
                        ),
                    )
                }
            }
        }

        private fun encodeMessages(messages: List<Message>) = JSONArray().apply {
            messages.forEach { message ->
                put(
                    JSONObject()
                        .put("role", message.role.name)
                        .put("text", message.text)
                        .put("language", message.languageCode ?: JSONObject.NULL)
                        .put("sentAt", message.sentAt),
                )
            }
        }

        private fun encodeDocuments(documents: List<SavedDocument>) = JSONArray().apply {
            documents.forEach { document ->
                put(
                    JSONObject()
                        .put("name", document.name)
                        .put("text", document.text)
                        .put("addedAt", document.addedAt),
                )
            }
        }

        private fun decodeMessages(array: JSONArray?): List<Message> = buildList {
            if (array == null) return@buildList
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val role = runCatching { Role.valueOf(item.optString("role")) }.getOrNull() ?: continue
                val text = item.stringOrNull("text") ?: continue
                val sentAt = item.optLong("sentAt", 0L).takeIf { it > 0 } ?: continue
                add(Message(role, text, item.stringOrNull("language"), sentAt))
            }
        }

        private fun decodeDocuments(array: JSONArray?): List<SavedDocument> = buildList {
            if (array == null) return@buildList
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.stringOrNull("name") ?: continue
                val text = item.stringOrNull("text") ?: continue
                val addedAt = item.optLong("addedAt", 0L).takeIf { it > 0 } ?: continue
                add(SavedDocument(name, text, addedAt))
            }
        }

        /** "23h", "5h", "40 min", "1 min": how long until something disappears. */
        fun describeRemaining(millis: Long): String {
            val minutes = (millis / 60_000).coerceAtLeast(1)
            return if (minutes >= 60) "${minutes / 60}h" else "$minutes min"
        }

        /** "just now", "12 min ago", "3h ago". */
        fun describeAgo(millis: Long): String {
            val minutes = millis / 60_000
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes min ago"
                else -> "${minutes / 60}h ago"
            }
        }
    }
}
