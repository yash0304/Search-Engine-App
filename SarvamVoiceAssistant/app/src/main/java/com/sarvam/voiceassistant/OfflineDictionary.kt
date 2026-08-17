package com.sarvam.voiceassistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
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
 */
class OfflineDictionary(private val context: Context) {

    @Volatile
    private var database: SQLiteDatabase? = null

    private companion object {
        const val TAG = "OfflineDictionary"
        const val ASSET = "dictionary.db.gz"
        const val FILENAME = "dictionary.db"
        const val MAX_SENSES = 8
    }

    /**
     * Looks a word up, trying its inflected form, then irregular bases from the database,
     * then regular suffix rules. Returns an empty list when the word genuinely is not there.
     */
    suspend fun lookup(raw: String): Pair<String?, List<Sense>> = withContext(Dispatchers.IO) {
        val db = open() ?: return@withContext null to emptyList()
        val candidates = WordForms.candidates(raw)
        if (candidates.isEmpty()) return@withContext null to emptyList()

        // Exact form first — "saw" is a noun in its own right, not only the past of "see".
        for (candidate in candidates) {
            val senses = sensesFor(db, candidate)
            if (senses.isNotEmpty()) return@withContext candidate to senses
        }

        // Irregular forms are stored explicitly rather than guessed, together with the part
        // of speech — "ran" is a verb form, so the verb senses of "run" must lead rather
        // than the baseball noun that happens to rank first overall.
        for (candidate in candidates) {
            for ((base, pos) in irregularBases(db, candidate)) {
                val senses = sensesFor(db, base, preferredPos = pos)
                if (senses.isNotEmpty()) return@withContext base to senses
            }
        }

        null to emptyList()
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
                    .also { database = it }
            }.onFailure { Log.e(TAG, "Could not open the offline dictionary", it) }
                .getOrNull()
        }
    }

    private fun expand(destination: File) {
        Log.i(TAG, "Expanding the dictionary from assets on first use")
        val temporary = File(destination.parentFile, "$FILENAME.tmp")

        context.assets.open(ASSET).use { asset ->
            GZIPInputStream(asset).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
        }

        // Rename only once fully written, so an interrupted first run cannot leave a
        // truncated database that would then be treated as valid.
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("Could not move the expanded dictionary into place")
        }
    }

    fun close() {
        synchronized(this) {
            database?.takeIf { it.isOpen }?.close()
            database = null
        }
    }
}
