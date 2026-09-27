// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

/** A language supported by the translation backend. */
data class TranslationLanguage(val code: String, val displayName: String)

/**
 * Languages supported by the DeepL **oneshot** backend that the DeepLX/DLX server proxies
 * (verified against the DeepL docs language list at time of writing, 2025/2026:
 * AR BG CS DA DE EL EN ES ET FI FR HE HU ID IT JA KO LT LV NB NL PL PT RO RU SK SL SV TH TR UK VI ZH
 * plus the regional variants below). The same codes are accepted by the official DeepL v2 API.
 *
 * Do not add languages here without verifying they are actually supported by the backend –
 * an unsupported code is rejected by the server and would show up as an error in the pill.
 */
object TranslationLanguages {
    /** Value used for "detect the source language automatically" (field is omitted in requests). */
    const val AUTO_DETECT = "auto"

    private val base = listOf(
        TranslationLanguage("AR", "Arabic"),
        TranslationLanguage("BG", "Bulgarian"),
        TranslationLanguage("CS", "Czech"),
        TranslationLanguage("DA", "Danish"),
        TranslationLanguage("DE", "German"),
        TranslationLanguage("EL", "Greek"),
        TranslationLanguage("EN", "English"),
        TranslationLanguage("ES", "Spanish"),
        TranslationLanguage("ET", "Estonian"),
        TranslationLanguage("FI", "Finnish"),
        TranslationLanguage("FR", "French"),
        TranslationLanguage("HE", "Hebrew"),
        TranslationLanguage("HU", "Hungarian"),
        TranslationLanguage("ID", "Indonesian"),
        TranslationLanguage("IT", "Italian"),
        TranslationLanguage("JA", "Japanese"),
        TranslationLanguage("KO", "Korean"),
        TranslationLanguage("LT", "Lithuanian"),
        TranslationLanguage("LV", "Latvian"),
        TranslationLanguage("NB", "Norwegian"),
        TranslationLanguage("NL", "Dutch"),
        TranslationLanguage("PL", "Polish"),
        TranslationLanguage("PT", "Portuguese"),
        TranslationLanguage("RO", "Romanian"),
        TranslationLanguage("RU", "Russian"),
        TranslationLanguage("SK", "Slovak"),
        TranslationLanguage("SL", "Slovenian"),
        TranslationLanguage("SV", "Swedish"),
        TranslationLanguage("TH", "Thai"),
        TranslationLanguage("TR", "Turkish"),
        TranslationLanguage("UK", "Ukrainian"),
        TranslationLanguage("VI", "Vietnamese"),
        TranslationLanguage("ZH", "Chinese"),
    )

    private val variants = listOf(
        TranslationLanguage("EN-GB", "English (UK)"),
        TranslationLanguage("EN-US", "English (US)"),
        TranslationLanguage("PT-BR", "Portuguese (Brazil)"),
        TranslationLanguage("PT-PT", "Portuguese (Portugal)"),
        TranslationLanguage("ZH-HANS", "Chinese (Simplified)"),
        TranslationLanguage("ZH-HANT", "Chinese (Traditional)"),
    )

    /** All supported languages, base languages first. */
    val all: List<TranslationLanguage> = base + variants

    /** Codes shown at the top of the picker, in this order. */
    private val popularCodes = listOf("EN", "ID", "JA", "KO", "ZH", "ES", "FR", "DE", "RU", "AR", "PT", "IT")

    private val byCode = all.associateBy { it.code }

    /**
     * Picker order: popular languages first (stable order), then the remaining languages
     * sorted by display name. Cheap to compute and covers the complete backend list.
     */
    fun orderedForPicker(): List<TranslationLanguage> {
        val popular = popularCodes.mapNotNull { byCode[it] }
        val rest = all.filterNot { it.code in popularCodes }.sortedBy { it.displayName }
        return popular + rest
    }

    fun displayNameFor(code: String): String =
        byCode[code.uppercase()]?.displayName ?: code.uppercase()

    /** True if [code] is [AUTO_DETECT] or a language code supported by the backend. */
    fun isValidCode(code: String): Boolean =
        code.equals(AUTO_DETECT, ignoreCase = true) || byCode.containsKey(code.uppercase())
}
