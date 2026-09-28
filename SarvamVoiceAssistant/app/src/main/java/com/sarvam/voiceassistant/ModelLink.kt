package com.sarvam.voiceassistant

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Finds where the model file really lives before handing it to Android's DownloadManager.
 *
 * Hugging Face answers a download with redirects to its storage CDN. DownloadManager follows
 * them but repeats every header, and sending the Hugging Face token to the CDN's pre-signed
 * link makes it refuse the request. So the Hugging Face hops are followed here, with the
 * token, and DownloadManager is given the first link off Hugging Face, without it.
 *
 * Checking first has a second benefit: a gated model fails here, in a second, with a clear
 * reason — not after DownloadManager has retried in the background for a minute.
 */
object ModelLink {

    sealed interface Result {
        /** @param sendToken whether [url] is still on Hugging Face, where the token belongs. */
        data class Found(val url: String, val sendToken: Boolean) : Result

        /** @param code the HTTP status, or 0 when Hugging Face could not be reached at all. */
        data class Failed(val code: Int) : Result
    }

    private const val MAX_HOPS = 6

    /** Blocking; call off the main thread. [client] must not follow redirects itself. */
    fun resolve(client: OkHttpClient, url: String, token: String): Result {
        val origin = url.toHttpUrl().host
        var current = url.toHttpUrl()

        repeat(MAX_HOPS) {
            val request = Request.Builder().url(current).head().apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }.build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                return Result.Failed(0)
            }

            response.use {
                when {
                    it.isRedirect -> {
                        val next = it.header("Location")?.let(current::resolve) ?: return Result.Failed(it.code)
                        if (next.host != origin) return Result.Found(next.toString(), sendToken = false)
                        current = next
                    }
                    it.isSuccessful -> return Result.Found(current.toString(), sendToken = true)
                    else -> return Result.Failed(it.code)
                }
            }
        }
        return Result.Failed(310) // Too many redirects.
    }
}
