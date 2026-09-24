package com.sarvam.voiceassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Reads a PDF with Sarvam Document Intelligence (Sarvam Vision): text, tables and layout in
 * 22 Indian languages and English.
 *
 * It is a job API rather than a single call. The steps and field names below are from the
 * official `sarvamai` SDK (v0.1.34), including two traps it documents: the job parameter is
 * `language`, not the `language_code` every other endpoint uses (that is silently ignored and
 * the document is read as Hindi), and the output format is `md`, not `markdown` (a 400).
 *
 *   1. POST /doc-digitization/job/v1                      create the job
 *   2. POST /doc-digitization/job/v1/upload-files         get a presigned upload URL
 *   3. PUT  <upload url>                                  the PDF, as an Azure block blob
 *   4. POST /doc-digitization/job/v1/{id}/start
 *   5. GET  /doc-digitization/job/v1/{id}/status          until finished
 *   6. POST /doc-digitization/job/v1/{id}/download-files  then GET the ZIP of Markdown
 */
class DocumentReader(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.sarvam.ai",
    private val pollIntervalMillis: Long = 2_000,
    private val timeoutMillis: Long = 5 * 60_000,
) {
    /** Languages Document Intelligence accepts as the primary language. */
    private val supported = setOf(
        "hi-IN", "en-IN", "bn-IN", "gu-IN", "kn-IN", "ml-IN", "mr-IN", "od-IN", "pa-IN", "ta-IN",
        "te-IN", "ur-IN", "as-IN", "brx-IN", "doi-IN", "ks-IN", "kok-IN", "mai-IN", "mni-IN",
        "ne-IN", "sa-IN", "sat-IN", "sd-IN",
    )

    /**
     * @param language the document's main language; unknown values fall back to English
     *   rather than to the API's Hindi default.
     * @param onProgress short status lines for the UI.
     * @return the document as Markdown.
     */
    suspend fun read(
        pdf: ByteArray,
        language: String,
        onProgress: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val primary = language.takeIf { it in supported } ?: "en-IN"

        onProgress("Uploading")
        val jobId = post(
            "doc-digitization/job/v1",
            JSONObject().put(
                "job_parameters",
                JSONObject().put("language", primary).put("output_format", "md"),
            ),
        ).stringOrNull("job_id") ?: throw SarvamException("Document reading did not return a job.")

        val uploadUrl = post(
            "doc-digitization/job/v1/upload-files",
            JSONObject().put("job_id", jobId).put("files", org.json.JSONArray().put(FILE_NAME)),
        ).firstFileUrl("upload_urls") ?: throw SarvamException("Document reading gave nowhere to upload.")

        upload(uploadUrl, pdf)

        onProgress("Reading")
        post("doc-digitization/job/v1/$jobId/start", JSONObject())

        val status = awaitCompletion(jobId)
        if (status.state == "Failed") {
            throw SarvamException("The document could not be read${status.error?.let { ": $it" } ?: "."}")
        }

        val downloadUrl = post("doc-digitization/job/v1/$jobId/download-files", JSONObject())
            .firstFileUrl("download_urls")
            ?: throw SarvamException("Document reading finished without any output.")

        val text = markdownFromZip(download(downloadUrl))
        if (text.isBlank()) throw SarvamException("No text was found in that document.")
        text
    }

    private data class Status(val state: String, val error: String?)

    private suspend fun awaitCompletion(jobId: String): Status {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val json = get("doc-digitization/job/v1/$jobId/status")
            val state = json.stringOrNull("job_state").orEmpty()
            if (state in FINISHED) return Status(state, json.stringOrNull("error_message"))
            if (System.currentTimeMillis() > deadline) {
                throw SarvamException("Reading the document is taking too long. Try a shorter one.")
            }
            delay(pollIntervalMillis)
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    private fun post(path: String, body: JSONObject): JSONObject = call(
        Request.Builder().url("$baseUrl/$path")
            .addHeader("api-subscription-key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build(),
    )

    private fun get(path: String): JSONObject = call(
        Request.Builder().url("$baseUrl/$path").addHeader("api-subscription-key", apiKey).get().build(),
    )

    private fun call(request: Request): JSONObject = try {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(body).let { it.stringOrNull("message") ?: it.optJSONObject("error")?.stringOrNull("message") }
                }.getOrNull()
                throw SarvamException(
                    when (response.code) {
                        401, 403 -> "Document reading rejected the API key."
                        402 -> "Your Sarvam account is out of credits."
                        else -> "Document reading failed: ${detail ?: "HTTP ${response.code}"}"
                    },
                )
            }
            runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        }
    } catch (e: IOException) {
        throw SarvamException("Document reading failed: check your internet connection.", e)
    }

    /** Presigned Azure URL: no API key, but the blob-type header is mandatory. */
    private fun upload(url: String, pdf: ByteArray) {
        val request = Request.Builder().url(url)
            .addHeader("x-ms-blob-type", "BlockBlob")
            .put(pdf.toRequestBody(PDF))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw SarvamException("Uploading the document failed (HTTP ${response.code}).")
            }
        } catch (e: IOException) {
            throw SarvamException("Uploading the document failed: check your internet connection.", e)
        }
    }

    private fun download(url: String): ByteArray = try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw SarvamException("Downloading the result failed (HTTP ${response.code}).")
            response.body?.bytes() ?: ByteArray(0)
        }
    } catch (e: IOException) {
        throw SarvamException("Downloading the result failed: check your internet connection.", e)
    }

    private fun JSONObject.firstFileUrl(field: String): String? {
        val urls = optJSONObject(field) ?: return null
        val first = urls.keys().asSequence().firstOrNull() ?: return null
        return urls.optJSONObject(first)?.stringOrNull("file_url")
    }

    companion object {
        private const val FILE_NAME = "document.pdf"
        private val FINISHED = setOf("Completed", "PartiallyCompleted", "Failed")
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val PDF = "application/pdf".toMediaType()

        /**
         * The Markdown files in the result ZIP, in name order (pages are numbered), joined.
         * Anything else in the archive — images of figures, metadata — is skipped.
         */
        fun markdownFromZip(zip: ByteArray): String {
            val pages = mutableListOf<Pair<String, String>>()
            ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.endsWith(".md", ignoreCase = true)) {
                        pages += entry.name to input.readBytes().toString(Charsets.UTF_8).trim()
                    }
                }
            }
            return pages
                .sortedBy { naturalKey(it.first) }
                .map { it.second }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
        }

        /** "page_2" before "page_10": pad every run of digits so text order is number order. */
        private fun naturalKey(name: String): String =
            Regex("\\d+").replace(name.lowercase()) { it.value.padStart(12, '0') }
    }
}
