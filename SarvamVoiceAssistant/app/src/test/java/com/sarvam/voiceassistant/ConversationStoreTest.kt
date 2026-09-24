package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConversationStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val file get() = File(folder.root, "conversation.json")

    private fun store() = ConversationStore(file) { now }

    private fun message(text: String, hoursAgo: Double, role: Role = Role.USER) =
        Message(role, text, "gu-IN", sentAt = now - (hoursAgo * hour).toLong())

    private fun chat(id: String, vararg messages: Message, documents: List<ConversationStore.SavedDocument> = emptyList()) =
        ConversationStore.Chat(id, "Chat $id", messages.first().sentAt, messages.toList(), documents)

    @Test
    fun separateChatsSurviveAReloadIntact() {
        val photo = ConversationStore.SavedDocument("bill.jpg", "Total ₹1,240", now - 3 * hour)
        store().save(
            listOf(
                chat("a", message("What is on this bill?", 3.0), message("₹1,240", 2.9, Role.ASSISTANT), documents = listOf(photo)),
                chat("b", message("Tell me about vermicelli", 1.0), message("A thin pasta…", 0.9, Role.ASSISTANT)),
            ),
        )

        val loaded = store().load()
        assertEquals(2, loaded.size)
        val b = loaded.single { it.id == "b" }
        assertEquals(listOf("Tell me about vermicelli", "A thin pasta…"), b.messages.map { it.text })
        // The photo belongs to its own chat only — the point of separate chats.
        assertTrue(b.documents.isEmpty())
        assertEquals("Total ₹1,240", loaded.single { it.id == "a" }.documents.single().text)
    }

    @Test
    fun mostRecentlyActiveChatComesFirst() {
        store().save(listOf(chat("old", message("x", 5.0)), chat("new", message("y", 0.5))))
        assertEquals(listOf("new", "old"), store().load().map { it.id })
    }

    @Test
    fun eachMessageDisappearsTwentyFourHoursAfterItWasSent() {
        store().save(listOf(chat("a", message("yesterday", 24.5), message("this morning", 5.0))))
        assertEquals(listOf("this morning"), store().load().single().messages.map { it.text })
    }

    @Test
    fun aChatGoesWhenItsLastMessageDoes() {
        store().save(listOf(chat("gone", message("x", 25.0)), chat("kept", message("y", 1.0))))
        assertEquals(listOf("kept"), store().load().map { it.id })
    }

    @Test
    fun sharedDocumentsExpireToo() {
        val old = ConversationStore.SavedDocument("old.pdf", "x", now - 25 * hour)
        store().save(listOf(chat("a", message("hi", 1.0), documents = listOf(old))))
        assertTrue(store().load().single().documents.isEmpty())
    }

    @Test
    fun whenEverythingHasExpiredTheFileIsDeleted() {
        store().save(listOf(chat("a", message("hi", 1.0))))
        assertTrue(file.exists())

        now += 24 * hour
        store().save(store().load())
        assertFalse("nothing should be kept once the day is up", file.exists())
    }

    @Test
    fun theOldSingleConversationBecomesOneChat() {
        val json = """{"version":1,"messages":[
            {"role":"USER","text":"What does this say?","language":"en-IN","sentAt":${now - hour}},
            {"role":"ASSISTANT","text":"It is a receipt.","language":"en-IN","sentAt":${now - hour + 1000}}
        ],"documents":[{"name":"receipt.jpg","text":"Total","addedAt":${now - hour}}]}"""
        file.writeText(json)

        val chats = store().load()
        assertEquals(1, chats.size)
        assertEquals(2, chats.single().messages.size)
        assertEquals("Read: receipt.jpg", chats.single().title)
    }

    @Test
    fun aCorruptFileIsNoHistoryNotACrash() {
        file.writeText("{not json")
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun unreadableEntriesAreSkippedNotFatal() {
        val json = """{"version":2,"chats":[{"id":"a","title":"t","createdAt":${now - hour},"messages":[
            {"role":"USER","text":"kept","sentAt":${now - hour}},
            {"role":"ROBOT","text":"bad role","sentAt":${now - hour}},
            {"role":"USER","text":null,"sentAt":${now - hour}},
            {"role":"USER","text":"no time"}
        ]},{"title":"no id"}]}"""
        file.writeText(json)
        assertEquals(listOf("kept"), store().load().single().messages.map { it.text })
    }

    @Test
    fun titlesComeFromHowTheChatStarted() {
        assertEquals("Read: receipt.jpg", ConversationStore.titleFor("anything", "receipt.jpg"))
        assertEquals("Tell me about vermicelli", ConversationStore.titleFor("  Tell me  about vermicelli ", null))
        val long = ConversationStore.titleFor("a".repeat(100), null)
        assertEquals(48, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("New chat", ConversationStore.titleFor(null, null))
    }

    @Test
    fun nextExpiryIsTheOldestMessagePlusADay() {
        val c = chat("a", message("a", 3.0), message("b", 1.0))
        assertEquals(now - 3 * hour + 24 * hour, c.nextExpiry())
        assertNull(ConversationStore.Chat("x", "t", now).nextExpiry())
    }

    @Test
    fun timesReadNaturally() {
        assertEquals("23h", ConversationStore.describeRemaining(23 * hour + 59 * 60_000))
        assertEquals("40 min", ConversationStore.describeRemaining(40 * 60_000))
        assertEquals("1 min", ConversationStore.describeRemaining(5_000))
        assertEquals("just now", ConversationStore.describeAgo(20_000))
        assertEquals("12 min ago", ConversationStore.describeAgo(12 * 60_000))
        assertEquals("3h ago", ConversationStore.describeAgo(3 * hour + 5 * 60_000))
    }
}
