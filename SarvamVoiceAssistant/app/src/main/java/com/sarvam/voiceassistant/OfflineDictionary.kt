package com.sarvam.voiceassistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A real dictionary, on the device, with no network.
 *
 * Asking a language model for a word's meaning invites invention — it will produce a
 * confident definition for a word that does not exist. WordNet either has the word or it
 * does not, and "not in the dictionary" is a correct answer this can actually give.
 *
 * The database ships gzipped in assets (~12 MB) and is expanded once on first use to
 * ~30 MB in app storage, because SQLite cannot read a compressed asset directly.
 *
 * The asset is deliberately NOT named `.gz`: the Android build pipeline inflates a `.gz`
 * asset and packages it under the name without that extension, so the file this code opened
 * was genuinely absent from the APK while a 30 MB `assets/dictionary.db` sat next to it.
 */
class OfflineDictionary(private val context: Context) {

    @Volatile
    private var database: SQLiteDatabase? = null

    /** Why the last open attempt failed, surfaced instead of pretending the word is absent. */
    @Volatile
    private var failureReason: String? = null

    private companion object {
        const val TAG = "OfflineDictionary"
        const val ASSET = "dictionary.db.bin"
        const val FILENAME = "dictionary.db"
        const val MAX_SENSES = 8

        /** The expanded database is about 30 MB; refuse early rather than fail part-written. */
        const val REQUIRED_BYTES = 35_000_000L
    }

    /** What the dictionary can report about itself, for the Settings screen. */
    sealed interface Status {
        data class Ready(val senseCount: Int) : Status
        data class Unavailable(val reason: String) : Status
    }

    /**
     * Opens the dictionary if needed and reports what happened. Exists so the app can show
     * the real reason on screen: a database that will not open is otherwise invisible, and
     * was being reported to the user as "that word is not in the dictionary".
     */
    suspend fun status(): Status = withContext(Dispatchers.IO) {
        val db = open()
            ?: return@withContext Status.Unavailable(failureReason ?: "unknown error")

        runCatching {
            db.rawQuery("SELECT COUNT(*) FROM sense", null).use { cursor ->
                cursor.moveToFirst()
                Status.Ready(cursor.getInt(0))
            }
        }.getOrElse { Status.Unavailable(it.message ?: "query failed") }
    }

    /**
     * Deletes the expanded database so the next lookup rebuilds it from the asset. For when
     * the first expansion was interrupted or ran out of space.
     */
    suspend fun rebuild(): Status = withContext(Dispatchers.IO) {
        close()
        failureReason = null
        runCatching { File(context.filesDir, FILENAME).delete() }
        status()
    }

    /**
     * Looks a word up, trying its inflected form, then irregular bases from the database,
     * then regular suffix rules. Returns an empty list when the word genuinely is not there.
     */
    suspend fun lookup(raw: String): DictionaryResult = withContext(Dispatchers.IO) {
        val db = open()
            ?: return@withContext DictionaryResult.Unavailable(failureReason ?: "unknown error")

        val candidates = WordForms.candidates(raw)
        if (candidates.isEmpty()) return@withContext DictionaryResult.NotFound

        try {
            // Exact form first — "saw" is a noun in its own right, not only the past of "see".
            for (candidate in candidates) {
                val senses = sensesFor(db, candidate)
                if (senses.isNotEmpty()) return@withContext DictionaryResult.Found(candidate, senses)
            }

            // Irregular forms are stored explicitly rather than guessed, together with the
            // part of speech — "ran" is a verb form, so the verb senses of "run" must lead
            // rather than the baseball noun that happens to rank first overall.
            for (candidate in candidates) {
                for ((base, pos) in irregularBases(db, candidate)) {
                    val senses = sensesFor(db, base, preferredPos = pos)
                    if (senses.isNotEmpty()) return@withContext DictionaryResult.Found(base, senses)
                }
            }
        } catch (e: Exception) {
            // A query failure is not the same as the word being absent, and must not be
            // reported as one.
            Log.e(TAG, "Dictionary query failed", e)
            return@withContext DictionaryResult.Unavailable(e.message ?: "query failed")
        }

        DictionaryResult.NotFound
    }

    private fun sensesFor(db: SQLiteDatabase, word: String, preferredPos: String? = null): List<Sense> {
        val senses = mutableListOf<Sense>()
        // Ordering by "is this the preferred part of speech" first, then WordNet's own rank.
        val order = if (preferredPos == null) "rank" else "(pos <> ?) , rank"
        val arguments = if (preferredPos == null) {
            arrayOf(word, MAX_SENSES.toString())
        } else {
            arrayOf(word, preferredPos, MAX_SENSES.toString())
        }

        db.rawQuery(
            "SELECT pos, definition, synonyms FROM sense WHERE word = ? ORDER BY $order LIMIT ?",
            arguments,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val (definition, examples) = DictionaryFormatting.splitGloss(cursor.getString(1))
                val synonyms = cursor.getString(2)
                    .split(",")
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                senses.add(Sense(cursor.getString(0), definition, synonyms, examples))
            }
        }
        return senses
    }

    /** Irregular bases for a form, each with the part of speech it inflects. */
    private fun irregularBases(db: SQLiteDatabase, form: String): List<Pair<String, String>> {
        val bases = mutableListOf<Pair<String, String>>()
        db.rawQuery("SELECT base, pos FROM morph WHERE form = ?", arrayOf(form)).use { cursor ->
            while (cursor.moveToNext()) bases.add(cursor.getString(0) to cursor.getString(1))
        }
        return bases
    }

    /** Opens the database, expanding it from assets on first use. Null if that fails. */
    private fun open(): SQLiteDatabase? {
        database?.let { if (it.isOpen) return it }

        return synchronized(this) {
            database?.takeIf { it.isOpen } ?: runCatching {
                val file = File(context.filesDir, FILENAME)
                if (!file.exists() || file.length() == 0L) expand(file)

                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                    .also {
                        database = it
                        failureReason = null
                    }
            }.onFailure {
                Log.e(TAG, "Could not open the offline dictionary", it)
                failureReason = it.message ?: it::class.java.simpleName
            }.getOrNull()
        }
    }

    private fun expand(destination: File) {
        val free = destination.parentFile?.usableSpace ?: 0L
        Log.i(TAG, "Expanding the dictionary from assets; ${free / 1_000_000} MB free")
        if (free in 1 until REQUIRED_BYTES) {
            error("needs about ${REQUIRED_BYTES / 1_000_000} MB free, only ${free / 1_000_000} MB available")
        }

        val temporary = File(destination.parentFile, "$FILENAME.tmp")

        // AssetManager throws FileNotFoundException whose message is only the file name,
        // which tells the user nothing. Say what it actually means, and list what the APK
        // does contain — "missing" alone cannot distinguish an asset that was never
        // packaged from one packaged under a name this code is not asking for.
        val asset = try {
            context.assets.open(ASSET)
        } catch (e: java.io.FileNotFoundException) {
            error("$ASSET is not in this build. Assets present: ${assetInventory()}")
        }

        // Whether to inflate is decided by the first two bytes, not by the file name. The
        // build pipeline has already rewritten this asset once — inflating it and renaming
        // it — and content is the only thing it cannot quietly change underneath us.
        asset.use { raw ->
            val buffered = BufferedInputStream(raw)
            val input = if (looksGzipped(buffered)) GZIPInputStream(buffered) else buffered
            input.use { source ->
                temporary.outputStream().use { output -> source.copyTo(output) }
            }
        }

        // Rename only once fully written, so an interrupted first run cannot leave a
        // truncated database that would then be treated as valid.
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("Could not move the expanded dictionary into place")
        }
    }

    /** The gzip magic number, read without consuming it. */
    private fun looksGzipped(stream: BufferedInputStream): Boolean {
        stream.mark(2)
        val first = stream.read()
        val second = stream.read()
        stream.reset()
        return first == 0x1f && second == 0x8b
    }

    /**
     * Everything the packaged APK actually has in `assets/`, for the failure message. The
     * root listing also contains directory names, so a nested asset still shows up as its
     * folder rather than vanishing.
     */
    private fun assetInventory(): String {
        val entries = runCatching { context.assets.list("")?.toList().orEmpty() }
            .getOrElse { return "could not be listed (${it.message ?: it::class.java.simpleName})" }

        if (entries.isEmpty()) return "none at all"
        return entries.sorted().joinToString(", ")
    }

    fun close() {
        synchronized(this) {
            database?.takeIf { it.isOpen }?.close()
            database = null
        }
    }
}
