package com.sarvam.voiceassistant

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ModelLinkTest {

    private lateinit var hub: MockWebServer
    private lateinit var cdn: MockWebServer
    private val client = OkHttpClient.Builder().followRedirects(false).build()

    @Before
    fun setUp() {
        hub = MockWebServer().apply { start() }
        cdn = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        hub.shutdown()
        cdn.shutdown()
    }

    /** The two servers differ only by port, so give the CDN a different host name. */
    private fun cdnUrl(path: String) = cdn.url(path).newBuilder().host("127.0.0.1").build().toString()

    private fun hubUrl(path: String) = hub.url(path).newBuilder().host("localhost").build().toString()

    @Test
    fun followsHubHopsWithTheTokenAndStopsAtTheCdn() {
        hub.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/api/resolve-cache/model.litertlm"))
        hub.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdnUrl("/signed?x=1")))

        val result = ModelLink.resolve(client, hubUrl("/repo/resolve/abc/model.litertlm"), "hf_secret")

        assertEquals(ModelLink.Result.Found(cdnUrl("/signed?x=1"), sendToken = false), result)
        assertEquals("Bearer hf_secret", hub.takeRequest().getHeader("Authorization"))
        assertEquals("/api/resolve-cache/model.litertlm", hub.takeRequest().path)
        assertEquals(0, cdn.requestCount) // The pre-signed link is DownloadManager's to fetch.
    }

    @Test
    fun noTokenMeansNoAuthorizationHeader() {
        hub.enqueue(MockResponse().setResponseCode(200))
        val url = hubUrl("/model.litertlm")

        assertEquals(ModelLink.Result.Found(url, sendToken = true), ModelLink.resolve(client, url, ""))
        assertNull(hub.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun aGatedModelReportsItsStatus() {
        hub.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ModelLink.Result.Failed(401), ModelLink.resolve(client, hubUrl("/m"), ""))
    }

    @Test
    fun anUnreachableHostIsCodeZero() {
        hub.shutdown()
        assertEquals(ModelLink.Result.Failed(0), ModelLink.resolve(client, hubUrl("/m"), ""))
    }
}
