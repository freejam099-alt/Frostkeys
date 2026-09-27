// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

/**
 * Result of a single translation request.
 *
 * Implementations of [TranslationEngine] must return a [Failure] instead of throwing for all
 * recoverable errors (network problems, HTTP errors, malformed responses, ...).
 *
 * Coroutine cancellation may (and should) propagate as a CancellationException.
 */
sealed interface TranslationResult {
    data class Success(
        val translatedText: String,
        val detectedSourceLanguage: String? = null,
    ) : TranslationResult

    data class Failure(
        val kind: TranslationFailureKind,
        /** Short, user-presentable message. Never contains user text. */
        val message: String,
    ) : TranslationResult
}

enum class TranslationFailureKind {
    /** No endpoint configured, or the configured endpoint is not a valid URL. */
    NOT_CONFIGURED,

    /** The cloud gatekeeper blocked the request (Cloud & AI features disabled). */
    CLOUD_DISABLED,

    /** IO / DNS / connection error. */
    NETWORK,

    /** Request timed out. */
    TIMEOUT,

    /** HTTP 429 – server side rate limiting. */
    RATE_LIMITED,

    /** HTTP 403 – blocked by the server. */
    FORBIDDEN,

    /** HTTP 413 – single chunk too long for the backend. */
    TOO_LONG,

    /** Any other server side error (5xx, unexpected status). */
    SERVER,

    /** Successful HTTP status but unusable body. */
    INVALID_RESPONSE,
}

/**
 * Complete state of the translate feature, exposed to the UI as a single source of truth.
 * (no scattered isLoading/hasError booleans)
 */
sealed interface TranslationUiState {
    data object Idle : TranslationUiState

    data class Translating(
        val sourceText: String,
        /** Last successfully translated text (kept visible to avoid flicker while retranslating). */
        val lastTranslation: String?,
        val doneChunks: Int,
        val totalChunks: Int,
    ) : TranslationUiState

    data class Success(
        val sourceText: String,
        val translatedText: String,
        val detectedSourceLanguage: String?,
    ) : TranslationUiState

    data class Error(
        /** Short, user-presentable message. Never contains user text. */
        val message: String,
        val lastTranslation: String?,
    ) : TranslationUiState
}
