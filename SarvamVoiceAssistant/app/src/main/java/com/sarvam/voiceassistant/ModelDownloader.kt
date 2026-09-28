package com.sarvam.voiceassistant

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads the on-device model with Android's DownloadManager, which keeps going when the
 * app is closed, shows progress in the notification shade and resumes after a dropped
 * connection — all of which matter for a 2.6 GB file.
 */
class ModelDownloader(context: Context) {

    private val appContext = context.applicationContext
    private val downloads = appContext.getSystemService(DownloadManager::class.java)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * App-specific external storage: no permission needed, removed with the app, and where
     * DownloadManager is allowed to write.
     */
    private val directory: File? get() = appContext.getExternalFilesDir(null)?.let { File(it, "models") }

    val file: File? get() = directory?.let { File(it, OnDeviceModel.FILE_NAME) }

    /** Reads DownloadManager's progress; cheap enough to poll every second. */
    fun status(): ModelStatus {
        val id = prefs.getLong(KEY_ID, NONE)
        if (id != NONE) {
            val progress = query(id)
            when (progress?.status) {
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED ->
                    return ModelStatus(ModelStatus.Phase.DOWNLOADING, progress.bytes)
                DownloadManager.STATUS_FAILED -> {
                    Log.w(TAG, "Model download failed, reason ${progress.reason}")
                    downloads.remove(id)
                    finish(OnDeviceModel.downloadFailure(progress.reason))
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val size = file?.length() ?: 0
                    finish(
                        if (OnDeviceModel.isComplete(size)) null
                        else "The download was incomplete ($size bytes). Tap Download to try again.",
                    )
                    if (!OnDeviceModel.isComplete(size)) file?.delete()
                }
                else -> finish(null) // Removed from the Downloads app, or the record is gone.
            }
        }

        val ready = file?.let { it.isFile && OnDeviceModel.isComplete(it.length()) } == true
        return if (ready) {
            ModelStatus(ModelStatus.Phase.READY)
        } else {
            ModelStatus(problem = prefs.getString(KEY_PROBLEM, null))
        }
    }

    /**
     * Starts the download.
     *
     * @param token an optional Hugging Face read token, needed only if the model is gated.
     * @return why it could not start, or null once it has.
     */
    suspend fun start(token: String): String? = withContext(Dispatchers.IO) {
        val dir = directory ?: return@withContext "Storage is unavailable right now."
        dir.mkdirs()
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (!OnDeviceModel.hasRoomFor(free)) {
            return@withContext "Not enough free storage: the model needs ${OnDeviceModel.sizeLabel()} " +
                "and this phone has %.1f GB free.".format(free / 1_000_000_000.0)
        }

        val link = when (val result = ModelLink.resolve(http, OnDeviceModel.DOWNLOAD_URL, token)) {
            is ModelLink.Result.Failed -> return@withContext OnDeviceModel.downloadFailure(result.code)
            is ModelLink.Result.Found -> result
        }

        val target = File(dir, OnDeviceModel.FILE_NAME)
        target.delete() // An old partial file would make DownloadManager write under another name.

        val request = DownloadManager.Request(Uri.parse(link.url))
            .setTitle("Boliyan offline model")
            .setDescription("${OnDeviceModel.NAME}, ${OnDeviceModel.sizeLabel()}")
            .setDestinationUri(Uri.fromFile(target))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        if (link.sendToken && token.isNotBlank()) request.addRequestHeader("Authorization", "Bearer $token")

        val id = downloads.enqueue(request)
        prefs.edit().putLong(KEY_ID, id).remove(KEY_PROBLEM).apply()
        null
    }

    /** Stops a download in progress and deletes what it had fetched. */
    fun cancel() {
        val id = prefs.getLong(KEY_ID, NONE)
        if (id != NONE) downloads.remove(id)
        file?.delete()
        finish(null)
    }

    /** Frees the 2.6 GB. */
    fun delete() {
        cancel()
    }

    private fun finish(problem: String?) {
        prefs.edit().remove(KEY_ID).apply {
            if (problem == null) remove(KEY_PROBLEM) else putString(KEY_PROBLEM, problem)
        }.apply()
    }

    private class Progress(val status: Int, val bytes: Long, val reason: Int)

    private fun query(id: Long): Progress? = runCatching {
        downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            Progress(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }
    }.getOrNull()

    private companion object {
        const val TAG = "ModelDownloader"
        const val PREFS = "on_device_model"
        const val KEY_ID = "download_id"
        const val KEY_PROBLEM = "problem"
        const val NONE = -1L
    }
}
