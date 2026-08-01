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

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    private companion object {
        const val TAG = "ApiKeyStore"
        const val FILE_ENCRYPTED = "sarvam_secure_prefs"
        const val FILE_PLAIN = "sarvam_prefs"
        const val KEY_API = "api_key"
        const val KEY_SPEAKER = "speaker"
        const val KEY_LANGUAGE = "input_language"

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
