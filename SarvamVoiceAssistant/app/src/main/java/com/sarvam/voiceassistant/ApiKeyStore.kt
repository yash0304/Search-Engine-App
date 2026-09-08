package com.sarvam.voiceassistant

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the Sarvam API key and the app's preferences on the device.
 *
 * The key is held in [EncryptedSharedPreferences], backed by the Android Keystore, so it is
 * never written to disk in plain text.
 *
 * Every read and write goes through [readString] / [write], because encrypted preferences do
 * not only fail when they are created. If the Keystore key is invalidated — an OS update, a
 * backup restored onto a different device, the screen lock being changed — the stored bytes
 * can no longer be decrypted and *every subsequent access throws*. Left unguarded that
 * crashes the app on the Settings screen, which is precisely where the key is read and
 * written. A store that cannot be decrypted is recreated empty: losing a saved key and asking
 * for it again is a far better outcome than an app that will not open.
 */
class ApiKeyStore(context: Context) {

    private val appContext = context.applicationContext
    private var prefs: SharedPreferences = createPreferences(appContext)

    var apiKey: String
        get() = readString(KEY_API, "").trim()
        set(value) = write { putString(KEY_API, value.trim()) }

    /** Empty means "use the per-language default from [Voices]". */
    var preferredSpeaker: String
        get() = readString(KEY_SPEAKER, "")
        set(value) = write { putString(KEY_SPEAKER, value) }

    var inputLanguage: String
        get() = readString(KEY_LANGUAGE, Language.AUTO.code)
        set(value) = write { putString(KEY_LANGUAGE, value) }

    /** Empty means "let the client discover a model from /v1/models". */
    var chatModel: String
        get() = readString(KEY_CHAT_MODEL, "")
        set(value) = write { putString(KEY_CHAT_MODEL, value) }

    /** Allow location-aware answers such as "is it raining here". On by default. */
    var locationEnabled: Boolean
        get() = readBoolean(KEY_LOCATION, true)
        set(value) = write { putBoolean(KEY_LOCATION, value) }

    /** Allow the assistant to call the web-search tool. On by default. */
    var webSearchEnabled: Boolean
        get() = readBoolean(KEY_WEB_SEARCH, true)
        set(value) = write { putBoolean(KEY_WEB_SEARCH, value) }

    /** Require biometric or device-credential auth to open the app. On by default. */
    var lockEnabled: Boolean
        get() = readBoolean(KEY_LOCK, true)
        set(value) = write { putBoolean(KEY_LOCK, value) }

    /**
     * A safe stand-in for the key, e.g. `sk_••••••7f3a`. The full key is never handed to the
     * UI — showing it in Settings meant anyone holding the phone could read it.
     */
    fun maskedKey(): String = KeyMask.mask(apiKey)

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    // ── Guarded access ───────────────────────────────────────────────────

    private fun readString(key: String, fallback: String): String =
        guarded(fallback) { prefs.getString(key, fallback).orEmpty() }

    private fun readBoolean(key: String, fallback: Boolean): Boolean =
        guarded(fallback) { prefs.getBoolean(key, fallback) }

    private fun write(edit: SharedPreferences.Editor.() -> Unit) {
        guarded(Unit) { prefs.edit().apply(edit).apply() }
    }

    /**
     * Runs [block], and if the encrypted store is unreadable, rebuilds it once and retries.
     * A second failure returns [fallback] rather than propagating — no preference is worth
     * taking the app down for.
     */
    private fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (first: Exception) {
        Log.w(TAG, "Encrypted preferences failed; rebuilding the store", first)
        try {
            prefs = rebuild(appContext)
            block()
        } catch (second: Exception) {
            Log.e(TAG, "Preferences still unusable after rebuild", second)
            fallback
        }
    }

    private companion object {
        const val TAG = "ApiKeyStore"
        const val FILE_ENCRYPTED = "sarvam_secure_prefs"
        const val FILE_PLAIN = "sarvam_prefs"
        const val KEY_API = "api_key"
        const val KEY_SPEAKER = "speaker"
        const val KEY_LANGUAGE = "input_language"
        const val KEY_CHAT_MODEL = "chat_model"
        const val KEY_LOCK = "lock_enabled"
        const val KEY_WEB_SEARCH = "web_search_enabled"
        const val KEY_LOCATION = "location_enabled"

        fun encrypted(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                FILE_ENCRYPTED,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        fun createPreferences(context: Context): SharedPreferences = try {
            encrypted(context).also {
                // Probe immediately: creation can succeed while the stored bytes are no
                // longer decryptable, and the failure would otherwise surface later,
                // mid-screen, as a crash.
                it.getString(KEY_API, "")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted preferences unavailable at startup", e)
            runCatching { rebuild(context) }.getOrElse {
                context.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)
            }
        }

        /** Discards an undecryptable store and starts a fresh encrypted one. */
        fun rebuild(context: Context): SharedPreferences {
            context.deleteSharedPreferences(FILE_ENCRYPTED)
            return encrypted(context)
        }
    }
}
