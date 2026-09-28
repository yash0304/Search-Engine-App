package com.sarvam.voiceassistant

/**
 * Where answers come from: Sarvam's cloud models, Gemma running on the phone, or whichever
 * fits the moment. Kept free of Android so every routing rule is unit-tested.
 */
enum class AnswerSource(val value: String, val label: String, val hint: String) {
    AUTOMATIC(
        "automatic",
        "Automatic (recommended)",
        "Sarvam when you're online; the on-device model when you're not, or when the network fails mid-question.",
    ),
    SARVAM(
        "sarvam",
        "Sarvam (cloud)",
        "Best answers and Indian-language quality, with web search, weather and translation. Needs internet.",
    ),
    ON_DEVICE(
        "on_device",
        "On-device (Gemma 4)",
        "Everything stays on the phone: Gemma hears you, reads photos and PDFs, and answers; the phone's " +
            "own voice speaks. Works offline. No web search or weather.",
    ),
    ;

    companion object {
        fun from(value: String?): AnswerSource = entries.firstOrNull { it.value == value } ?: AUTOMATIC
    }
}

/** Which engine answers one particular question. */
sealed interface Route {
    data object Sarvam : Route
    data object OnDevice : Route

    /** Nothing can answer; [reason] is shown to the user as is. */
    data class Unavailable(val reason: String) : Route
}

object Routing {

    /**
     * @param online whether the phone has a working internet connection right now.
     * @param modelReady whether the on-device model is downloaded.
     * @param hasSarvamKey whether a Sarvam API key is saved.
     */
    fun decide(source: AnswerSource, online: Boolean, modelReady: Boolean, hasSarvamKey: Boolean): Route =
        when (source) {
            AnswerSource.SARVAM -> when {
                !hasSarvamKey -> Route.Unavailable("Add your Sarvam API key in Settings first.")
                !online -> Route.Unavailable(
                    if (modelReady) {
                        "You're offline. Switch \"Answers from\" to Automatic or On-device in Settings to use the offline model."
                    } else {
                        "You're offline. Download the on-device model in Settings to get answers without internet."
                    },
                )
                else -> Route.Sarvam
            }

            AnswerSource.ON_DEVICE ->
                if (modelReady) Route.OnDevice
                else Route.Unavailable("Download the on-device model in Settings first (about 2.6 GB, once).")

            AnswerSource.AUTOMATIC -> when {
                online && hasSarvamKey -> Route.Sarvam
                modelReady -> Route.OnDevice
                !hasSarvamKey -> Route.Unavailable("Add your Sarvam API key in Settings, or download the on-device model.")
                else -> Route.Unavailable(
                    "You're offline. Download the on-device model in Settings to get answers without internet.",
                )
            }
        }

    /**
     * Whether [error] came from the network rather than from Sarvam — the client wraps an
     * IOException in a SarvamException with a friendlier message.
     */
    fun isNetworkFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { it is java.io.IOException }

    /**
     * In Automatic mode, a Sarvam request that fails for lack of network is retried on the
     * phone — a tunnel mid-question should cost the cloud answer, not the answer.
     */
    fun fallBackToDevice(source: AnswerSource, modelReady: Boolean, networkFailure: Boolean): Boolean =
        source == AnswerSource.AUTOMATIC && modelReady && networkFailure
}
