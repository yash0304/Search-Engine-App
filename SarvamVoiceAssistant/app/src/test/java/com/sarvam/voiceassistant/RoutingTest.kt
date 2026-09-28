package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {

    private fun decide(source: AnswerSource, online: Boolean, model: Boolean, key: Boolean = true) =
        Routing.decide(source, online, model, key)

    @Test
    fun automaticUsesSarvamOnlineAndThePhoneOffline() {
        assertEquals(Route.Sarvam, decide(AnswerSource.AUTOMATIC, online = true, model = true))
        assertEquals(Route.OnDevice, decide(AnswerSource.AUTOMATIC, online = false, model = true))
    }

    @Test
    fun automaticWithoutAKeyStillWorksOnThePhone() {
        assertEquals(Route.OnDevice, decide(AnswerSource.AUTOMATIC, online = true, model = true, key = false))
    }

    @Test
    fun automaticOfflineWithoutTheModelSaysWhatToDo() {
        val route = decide(AnswerSource.AUTOMATIC, online = false, model = false)
        assertTrue(route is Route.Unavailable)
        assertTrue((route as Route.Unavailable).reason.contains("Download the on-device model"))
    }

    @Test
    fun onDeviceNeverTouchesTheNetwork() {
        assertEquals(Route.OnDevice, decide(AnswerSource.ON_DEVICE, online = true, model = true))
        assertEquals(Route.OnDevice, decide(AnswerSource.ON_DEVICE, online = false, model = true, key = false))
    }

    @Test
    fun onDeviceWithoutTheModelAsksForTheDownload() {
        assertTrue(decide(AnswerSource.ON_DEVICE, online = true, model = false) is Route.Unavailable)
    }

    @Test
    fun sarvamOfflinePointsToTheOfflineModelWhenThereIsOne() {
        val withModel = decide(AnswerSource.SARVAM, online = false, model = true) as Route.Unavailable
        assertTrue(withModel.reason.contains("Switch"))
        val without = decide(AnswerSource.SARVAM, online = false, model = false) as Route.Unavailable
        assertTrue(without.reason.contains("Download"))
    }

    @Test
    fun sarvamWithoutAKeyAsksForTheKey() {
        val route = decide(AnswerSource.SARVAM, online = true, model = true, key = false) as Route.Unavailable
        assertTrue(route.reason.contains("API key"))
    }

    @Test
    fun onlyAutomaticFallsBackMidQuestion() {
        assertTrue(Routing.fallBackToDevice(AnswerSource.AUTOMATIC, modelReady = true, networkFailure = true))
        assertFalse(Routing.fallBackToDevice(AnswerSource.SARVAM, modelReady = true, networkFailure = true))
        assertFalse(Routing.fallBackToDevice(AnswerSource.AUTOMATIC, modelReady = false, networkFailure = true))
        assertFalse(Routing.fallBackToDevice(AnswerSource.AUTOMATIC, modelReady = true, networkFailure = false))
    }

    @Test
    fun unknownStoredValueMeansAutomatic() {
        assertEquals(AnswerSource.AUTOMATIC, AnswerSource.from("mystery"))
        assertEquals(AnswerSource.AUTOMATIC, AnswerSource.from(null))
        assertEquals(AnswerSource.ON_DEVICE, AnswerSource.from("on_device"))
    }

    @Test
    fun aWrappedConnectionFailureCountsAsNetwork() {
        val wrapped = SarvamException("Request failed", java.net.UnknownHostException("api.sarvam.ai"))
        assertTrue(Routing.isNetworkFailure(wrapped))
        assertFalse(Routing.isNetworkFailure(SarvamException("Assistant reply failed (HTTP 429)")))
    }
}
