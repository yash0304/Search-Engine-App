package com.sarvam.voiceassistant

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runs the whole six-step job against a local server that behaves as the SDK describes. */
class DocumentReaderTest {

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var statusCalls = 0
    private var finalState = "Completed"
    private var resultZip = zip("page_2.md" to "Second", "page_10.md" to "Tenth", "page_1.md" to "# First")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val base = server.url("/").toString().trimEnd('/')
                return when {
                    request.path == "/doc-digitization/job/v1" -> json("""{"job_id":"job-1","job_state":"Accepted"}""")
                    request.path == "/doc-digitization/job/v1/upload-files" ->
                        json("""{"job_id":"job-1","upload_urls":{"document.pdf":{"file_url":"$base/blob/upload"}}}""")
                    request.path == "/blob/upload" -> MockResponse().setResponseCode(201)
                    request.path == "/doc-digitization/job/v1/job-1/start" -> json("""{"job_state":"Pending"}""")
                    request.path == "/doc-digitization/job/v1/job-1/status" -> {
                        statusCalls++
                        val state = if (statusCalls < 3) "Running" else finalState
                        json("""{"job_id":"job-1","job_state":"$state","error_message":"page 1 unreadable"}""")
                    }
                    request.path == "/doc-digitization/job/v1/job-1/download-files" ->
                        json("""{"job_id":"job-1","download_urls":{"output.zip":{"file_url":"$base/blob/result"}}}""")
                    request.path == "/blob/result" -> MockResponse().setBody(Buffer().write(resultZip))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun reader() = DocumentReader(
        apiKey = "test-key",
        client = OkHttpClient(),
        baseUrl = server.url("/").toString().trimEnd('/'),
        pollIntervalMillis = 10,
    )

    @Test
    fun readsADocumentEndToEndInPageOrder() = runBlocking {
        val pdf = "%PDF-1.4 fake".toByteArray()
        val progress = mutableListOf<String>()

        val text = reader().read(pdf, "gu-IN") { progress += it }

        // page_10 must come after page_2, which plain alphabetical order gets wrong.
        assertEquals("# First\n\nSecond\n\nTenth", text)
        assertEquals(listOf("Uploading", "Reading"), progress)
        assertEquals(3, statusCalls)

        val create = JSONObject(requests.first { it.path == "/doc-digitization/job/v1" }.body.readUtf8())
        val params = create.getJSONObject("job_parameters")
        assertEquals("gu-IN", params.getString("language"))
        assertTrue("must be 'language', not 'language_code'", !params.has("language_code"))
        assertEquals("md", params.getString("output_format"))

        val upload = requests.first { it.path == "/blob/upload" }
        assertEquals("PUT", upload.method)
        assertEquals("BlockBlob", upload.getHeader("x-ms-blob-type"))
        assertTrue(upload.getHeader("Content-Type")!!.startsWith("application/pdf"))
        assertArrayEquals(pdf, upload.body.readByteArray())
        assertTrue("presigned URL must not receive the API key", upload.getHeader("api-subscription-key") == null)

        val apiCalls = requests.filter { it.path!!.startsWith("/doc-digitization") }
        assertTrue(apiCalls.all { it.getHeader("api-subscription-key") == "test-key" })
    }

    @Test
    fun unsupportedLanguageFallsBackToEnglishNotTheApisHindiDefault() = runBlocking {
        reader().read("%PDF".toByteArray(), "unknown")
        val create = JSONObject(requests.first { it.path == "/doc-digitization/job/v1" }.body.readUtf8())
        assertEquals("en-IN", create.getJSONObject("job_parameters").getString("language"))
    }

    @Test
    fun failedJobReportsTheServersReason() = runBlocking {
        finalState = "Failed"
        try {
            reader().read("%PDF".toByteArray(), "en-IN")
            fail("expected SarvamException")
        } catch (e: SarvamException) {
            assertEquals("The document could not be read: page 1 unreadable", e.message)
        }
    }

    @Test
    fun documentWithNoTextSaysSo() = runBlocking {
        resultZip = zip("figure.png" to "not text")
        try {
            reader().read("%PDF".toByteArray(), "en-IN")
            fail("expected SarvamException")
        } catch (e: SarvamException) {
            assertEquals("No text was found in that document.", e.message)
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
