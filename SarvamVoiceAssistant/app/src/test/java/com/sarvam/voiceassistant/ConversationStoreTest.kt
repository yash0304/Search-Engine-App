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

    private fun store(file: File = File(folder.root, "conversation.json")) = ConversationStore(file) { now }

    private fun message(text: String, hoursAgo: Double, role: Role = Role.USER) =
        Message(role, text, "gu-IN", sentAt = now - (hoursAgo * hour).toLong())

    @Test
    fun conversationSurvivesAReload() {
        val snapshot = ConversationStore.Snapshot(
            messages = listOf(message("કેમ છો", 2.0), message("મજામાં", 1.9, Role.ASSISTANT)),
            documents = listOf(ConversationStore.SavedDocument("bill.pdf", "Total ₹1,240", now - hour)),
        )
        store().save(snapshot)

        val loaded = store().load()
        assertEquals(listOf("કેમ છો", "મજામાં"), loaded.messages.map { it.text })
        assertEquals(listOf(Role.USER, Role.ASSISTANT), loaded.messages.map { it.role })
        assertEquals("gu-IN", loaded.messages.first().languageCode)
        assertEquals(snapshot.messages.first().sentAt, loaded.messages.first().sentAt)
        assertEquals("Total ₹1,240", loaded.documents.single().text)
    }

    @Test
    fun eachMessageDisappearsTwentyFourHoursAfterItWasSent() {
        store().save(
            ConversationStore.Snapshot(
                messages = listOf(message("yesterday", 24.5), message("this morning", 5.0)),
            ),
        )
        assertEquals(listOf("this morning"), store().load().messages.map { it.text })
    }

    @Test
    fun sharedDocumentsExpireToo() {
        val snapshot = ConversationStore.Snapshot(
            messages = listOf(message("hi", 1.0)),
            documents = listOf(ConversationStore.SavedDocument("old.pdf", "x", now - 25 * hour)),
        )
        store().save(snapshot)
        assertTrue(store().load().documents.isEmpty())
    }

    @Test
    fun whenEverythingHasExpiredTheFileIsDeleted() {
        val file = File(folder.root, "conversation.json")
        store(file).save(ConversationStore.Snapshot(messages = listOf(message("hi", 1.0))))
        assertTrue(file.exists())

        now += 24 * hour
        store(file).save(store(file).load())
        assertFalse("nothing should be kept once the day is up", file.exists())
    }

    @Test
    fun clearRemovesEverythingImmediately() {
        val file = File(folder.root, "conversation.json")
        store(file).save(ConversationStore.Snapshot(messages = listOf(message("hi", 1.0))))
        store(file).clear()
        assertFalse(file.exists())
        assertTrue(store(file).load().messages.isEmpty())
    }

    @Test
    fun aCorruptFileIsNoHistoryNotACrash() {
        val file = File(folder.root, "conversation.json").apply { writeText("{not json") }
        assertTrue(store(file).load().messages.isEmpty())
    }

    @Test
    fun unreadableEntriesAreSkippedNotFatal() {
        val json = """{"version":1,"messages":[
            {"role":"USER","text":"kept","sentAt":${now - hour}},
            {"role":"ROBOT","text":"bad role","sentAt":${now - hour}},
            {"role":"USER","text":null,"sentAt":${now - hour}},
            {"role":"USER","text":"no time"}
        ]}"""
        val file = File(folder.root, "conversation.json").apply { writeText(json) }
        assertEquals(listOf("kept"), store(file).load().messages.map { it.text })
    }

    @Test
    fun nextExpiryIsTheOldestMessagePlusADay() {
        val snapshot = ConversationStore.Snapshot(messages = listOf(message("a", 3.0), message("b", 1.0)))
        assertEquals(now - 3 * hour + 24 * hour, snapshot.nextExpiry())
        assertNull(ConversationStore.Snapshot().nextExpiry())
    }

    @Test
    fun remainingTimeReadsNaturally() {
        assertEquals("23h", ConversationStore.describeRemaining(23 * hour + 59 * 60_000))
        assertEquals("1h", ConversationStore.describeRemaining(hour))
        assertEquals("40 min", ConversationStore.describeRemaining(40 * 60_000))
        assertEquals("1 min", ConversationStore.describeRemaining(5_000))
    }
}
