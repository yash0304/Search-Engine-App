package com.sarvam.voiceassistant

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Gates the app behind the device's own biometric or screen-lock credential.
 *
 * The API key is the thing worth protecting: it bills to the owner's Sarvam account, so
 * anyone who picks up an unlocked phone should not be able to spend against it.
 */
class AppLock(private val activity: FragmentActivity) {

    private val manager = BiometricManager.from(activity)

    /**
     * Combining a biometric with DEVICE_CREDENTIAL is only supported from API 30. On 28-29
     * that combination throws, so fall back to biometric alone with a cancel button.
     */
    private val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_WEAK
        }

    /** False when the device has no enrolled fingerprint, face, PIN, pattern or password. */
    fun isAvailable(): Boolean =
        manager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .apply {
                // setNegativeButtonText is rejected when DEVICE_CREDENTIAL is allowed.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) setNegativeButtonText("Cancel")
            }
            .build()

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure(errString.toString())
                }
            },
        )

        prompt.authenticate(info)
    }
}
