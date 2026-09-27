// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

/**
 * Abstraction over the translation backend so the engine can be replaced without touching
 * the controller or the UI layer.
 *
 * Contract:
 * - Must not block the caller thread; implementations do their IO on a background dispatcher.
 * - Must not throw for recoverable errors; return [TranslationResult.Failure] instead.
 * - Should honour coroutine cancellation (cancel the underlying HTTP call).
 * - Must never log user text, translations, credentials or tokens.
 */
interface TranslationEngine {
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationResult
}
