// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

import android.content.Context
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.latin.utils.prefs

/**
 * Process wide singleton owning the [TranslationController].
 *
 * - constructed lazily on first use (from the IME), holds only the application context
 * - survives view recreation (rotation) and input view restarts, so state/language choice persist
 * - the editor bridge is attached/detached explicitly by the IME (no service leak)
 */
object Translator {
    @Volatile
    private var controller: TranslationController? = null

    fun instance(): TranslationController? = controller

    fun isEnabled(): Boolean = controller?.enabled?.value == true

    fun isBlocked(): Boolean = controller?.blocked?.value ?: true

    fun currentState(): TranslationUiState = controller?.state?.value ?: TranslationUiState.Idle

    fun initialize(context: Context): TranslationController {
        controller?.let { return it }
        synchronized(this) {
            controller?.let { return it }
            val appContext = context.applicationContext
            val prefs = appContext.prefs()

            val engine = DeepLXTranslationEngine(
                endpointProvider = {
                    prefs.getString(TranslatePrefs.PREF_TRANSLATE_ENDPOINT, TranslatePrefs.DEFAULT_ENDPOINT).orEmpty()
                },
                apiKeyProvider = {
                    prefs.getString(TranslatePrefs.PREF_TRANSLATE_API_KEY, "").orEmpty()
                },
                cloudEnabledProvider = {
                    CloudManager.isFeatureAllowed(appContext, CloudManager.CloudFeature.TRANSLATION)
                },
            )

            val created = TranslationController(engine)
            created.restoreLanguages(
                prefs.getString(TranslatePrefs.PREF_TRANSLATE_SOURCE_LANG, TranslatePrefs.DEFAULT_SOURCE_LANG).orEmpty(),
                prefs.getString(TranslatePrefs.PREF_TRANSLATE_TARGET_LANG, TranslatePrefs.DEFAULT_TARGET_LANG).orEmpty(),
                prefs.getBoolean(TranslatePrefs.PREF_TRANSLATE_LIVE_MODE, TranslatePrefs.DEFAULT_LIVE_MODE),
            )
            created.onLanguageChanged = { source, target ->
                prefs.edit()
                    .putString(TranslatePrefs.PREF_TRANSLATE_SOURCE_LANG, source)
                    .putString(TranslatePrefs.PREF_TRANSLATE_TARGET_LANG, target)
                    .apply()
            }
            created.onLiveModeChanged = { live ->
                prefs.edit().putBoolean(TranslatePrefs.PREF_TRANSLATE_LIVE_MODE, live).apply()
            }
            controller = created
            return created
        }
    }

    fun toggle(context: Context) {
        initialize(context).toggle()
    }

    fun attachEditor(context: Context, bridge: EditorBridge?) {
        initialize(context).attachEditor(bridge)
    }

    fun detachEditor() {
        controller?.attachEditor(null)
    }

    fun onStartInputView() {
        controller?.onStartInputView()
    }

    fun onFinishInputView() {
        controller?.onFinishInputView()
    }

    fun onEditorTextMayHaveChanged() {
        controller?.onEditorTextMayHaveChanged()
    }
}
