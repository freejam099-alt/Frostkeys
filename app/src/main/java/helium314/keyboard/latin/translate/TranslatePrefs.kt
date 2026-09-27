// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

/**
 * Preference keys and defaults for the translate feature.
 *
 * Nothing here is secret: the endpoint is a server URL and the optional API key is provided
 * by the user in settings (see CloudScreen), never baked into the source code.
 */
object TranslatePrefs {
    const val PREF_TRANSLATE_ENDPOINT = "pref_translate_endpoint"
    const val PREF_TRANSLATE_API_KEY = "pref_translate_api_key"
    const val PREF_TRANSLATE_SOURCE_LANG = "pref_translate_source_lang"
    const val PREF_TRANSLATE_TARGET_LANG = "pref_translate_target_lang"
    const val PREF_TRANSLATE_LIVE_MODE = "pref_translate_live_mode"

    /**
     * Default: a self hosted DLX instance (default port 1188). DLX is the translation backend
     * chosen for this project; the endpoint can be any DLX/DeepL compatible server and is
     * changeable in settings without a code change.
     */
    const val DEFAULT_ENDPOINT = "http://127.0.0.1:1188/translate"

    const val DEFAULT_SOURCE_LANG = TranslationLanguages.AUTO_DETECT
    const val DEFAULT_TARGET_LANG = "EN"
    const val DEFAULT_LIVE_MODE = true
}
