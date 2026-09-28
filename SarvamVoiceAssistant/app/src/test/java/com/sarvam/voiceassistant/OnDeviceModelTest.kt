package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class OnDeviceModelTest {

    private val now = ZonedDateTime.of(2026, 9, 28, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    @Test
    fun downloadsThePinnedRevisionOfTheFileGalleryUses() {
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
                "6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm",
            OnDeviceModel.DOWNLOAD_URL,
        )
        assertEquals("2.6 GB", OnDeviceModel.sizeLabel())
    }

    @Test
    fun onlyAFileOfExactlyTheRightSizeCountsAsInstalled() {
        assertTrue(OnDeviceModel.isComplete(2_588_147_712L))
        assertFalse(OnDeviceModel.isComplete(2_588_147_711L))
        assertFalse(OnDeviceModel.isComplete(0))
    }

    @Test
    fun promptCarriesTheDateAndAdmitsItIsOffline() {
        val prompt = OnDeviceModel.systemInstruction(now)
        assertTrue(prompt.contains("Monday, 28 September 2026"))
        assertTrue(prompt.contains("cannot search the web"))
    }

    @Test
    fun aSharedDocumentIsIncludedButBounded() {
        val prompt = OnDeviceModel.systemInstruction(now, listOf("bill.jpg" to "x".repeat(10_000)))
        assertTrue(prompt.contains("bill.jpg"))
        assertTrue(prompt.contains("Use it only for questions"))
        assertTrue(prompt.length < 6_000)
    }

    @Test
    fun onlyRecentTurnsAreReplayed() {
        val messages = (1..20).map { Message(Role.USER, "m$it") }
        val kept = OnDeviceModel.history(messages)
        assertEquals(8, kept.size)
        assertEquals("m13", kept.first().text)
        assertEquals("m20", kept.last().text)
    }

    @Test
    fun repliesAreCleaned() {
        assertEquals("Namaste!", OnDeviceModel.cleanReply("<think>greet</think>\n  Namaste!  "))
        assertNull(OnDeviceModel.cleanReply("   "))
    }

    @Test
    fun replayedHistoryStartsWithAQuestion() {
        val messages = listOf(
            Message(Role.ASSISTANT, "I've read the bill."),
            Message(Role.USER, "Total?"),
            Message(Role.ASSISTANT, "Rs 450."),
        )
        assertEquals(listOf("Total?", "Rs 450."), OnDeviceModel.history(messages).map { it.text })
    }

    @Test
    fun gatedDownloadsExplainTheToken() {
        assertTrue(OnDeviceModel.downloadFailure(401).contains("token"))
        assertTrue(OnDeviceModel.downloadFailure(403).contains("accept"))
        assertTrue(OnDeviceModel.downloadFailure(1006).contains("storage"))
    }

    @Test
    fun aFullPhoneIsRefusedUpFront() {
        assertFalse(OnDeviceModel.hasRoomFor(2_700_000_000L))
        assertTrue(OnDeviceModel.hasRoomFor(3_000_000_000L))
    }

    @Test
    fun statusLinesReadNaturally() {
        assertEquals("Starting download…", ModelStatus(ModelStatus.Phase.DOWNLOADING).describe())
        assertEquals(
            "Downloading… 50% (1.3 of 2.6 GB)",
            ModelStatus(ModelStatus.Phase.DOWNLOADING, downloadedBytes = 1_294_073_856L).describe(),
        )
        assertEquals("Oops", ModelStatus(problem = "Oops").describe())
        assertTrue(ModelStatus(ModelStatus.Phase.READY).ready)
    }

    @Test
    fun transcriptsLoseTheirWrappers() {
        assertEquals("કેમ છો?", OnDeviceModel.cleanTranscript("Transcription: \"કેમ છો?\""))
        assertEquals("aaj kya banau", OnDeviceModel.cleanTranscript("  “aaj kya banau”  "))
        assertNull(OnDeviceModel.cleanTranscript("<think>silence</think>  "))
    }

    @Test
    fun onePageIsJustItsText() {
        assertEquals("Total: Rs 450", OnDeviceModel.joinPages(listOf(" Total: Rs 450 "), 1))
    }

    @Test
    fun longDocumentsSayWhatWasLeftOut() {
        val text = OnDeviceModel.joinPages(listOf("a", "b"), 23)
        assertTrue(text.startsWith("--- Page 1 ---\na\n\n--- Page 2 ---\nb"))
        assertTrue(text.contains("first 2 of 23 pages"))
    }
}
