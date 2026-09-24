package com.sarvam.voiceassistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the dictionary on a real Android runtime: the packaged asset, AssetManager, the
 * expansion into app storage and Android's own SQLite.
 *
 * Every earlier check stopped short of this. The asset was verified on disk, in git and
 * inside the APK, and the dictionary still failed on the phone, because the one thing never
 * exercised was the app actually opening it. This is that check.
 */
@RunWith(AndroidJUnit4::class)
class OfflineDictionaryDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun expandsFromThePackagedAssetAndReportsReady() = runBlocking {
        // rebuild() deletes any expanded copy first, so this exercises the full first-run
        // path rather than reusing a database a previous test left behind.
        val status = OfflineDictionary(context).rebuild()

        assertTrue("Expected Ready but was $status", status is OfflineDictionary.Status.Ready)
        assertEquals(207_235, (status as OfflineDictionary.Status.Ready).senseCount)
    }

    @Test
    fun definesWeather() = runBlocking {
        // The exact query that was reported failing.
        val result = OfflineDictionary(context).lookup("weather")

        assertTrue("Expected Found but was $result", result is DictionaryResult.Found)
        val found = result as DictionaryResult.Found
        assertEquals("weather", found.matchedForm)
        assertTrue(found.senses.first().definition.contains("atmospheric conditions"))
    }

    @Test
    fun resolvesAnIrregularFormToItsVerb() = runBlocking {
        val result = OfflineDictionary(context).lookup("ran")

        assertTrue("Expected Found but was $result", result is DictionaryResult.Found)
        val found = result as DictionaryResult.Found
        assertEquals("run", found.matchedForm)
        assertEquals("v", found.senses.first().partOfSpeech)
    }

    @Test
    fun reportsAnInventedWordAsNotFoundRatherThanUnavailable() = runBlocking {
        val result = OfflineDictionary(context).lookup("qwzxvbnt")

        assertEquals(DictionaryResult.NotFound, result)
    }
}
