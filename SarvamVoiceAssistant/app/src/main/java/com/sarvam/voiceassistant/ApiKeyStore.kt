package com.sarvam.voiceassistant

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the Sarvam API key and voice preference on the device.
 *
 * The key is held in [EncryptedSharedPreferences], backed by the Android Keystore, so it is
 * never written to disk in plain text and never committed to source control. If the Keystore
 * is unavailable — which happens on a few OEM builds and on some rooted devices — this falls
 * back to ordinary preferences rather than making the app unusable.
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences = createPreferences(context)

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    /** Empty means "use the per-language default from [Voices]". */
    var preferredSpeaker: String
        get() = prefs.getString(KEY_SPEAKER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SPEAKER, value).apply()

    var inputLanguage: String
        get() = prefs.getString(KEY_LANGUAGE, Language.AUTO.code).orEmpty()
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    /** Empty means "let the client discover a model from /v1/models". */
    var chatModel: String
        get() = prefs.getString(KEY_CHAT_MODEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CHAT_MODEL, value).apply()

    /** Allow location-aware answers such as "is it raining here". On by default. */
    var locationEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCATION, true)
        set(value) = prefs.edit().putBoolean(KEY_LOCATION, value).apply()

    /** Allow the assistant to call the web-search tool. On by default. */
    var webSearchEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEB_SEARCH, true)
        set(value) = prefs.edit().putBoolean(KEY_WEB_SEARCH, value).apply()

    /** Require biometric or device-credential auth to open the app. On by default. */
    var lockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_LOCK, value).apply()

    /**
     * A safe stand-in for the key, e.g. `sk_••••••7f3a`. The full key is never handed to the
     * UI — showing it in Settings meant anyone holding the phone could read it.
     */
    fun maskedKey(): String = KeyMask.mask(apiKey)

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

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

        fun createPreferences(context: Context): SharedPreferences = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                FILE_ENCRYPTED,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Keystore unavailable, falling back to plain preferences", e)
            context.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)
        }
    }
}
