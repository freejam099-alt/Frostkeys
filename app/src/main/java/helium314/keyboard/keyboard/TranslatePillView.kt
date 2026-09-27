// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.translate.TranslationLanguages
import helium314.keyboard.latin.translate.TranslationUiState
import helium314.keyboard.latin.translate.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Compact floating pill that lives inside the toolbar / suggestion strip area while Translate is
 * active. It never touches the keyboard keys and never triggers a keyboard relayout: it simply
 * overlays the 50dp strip container.
 *
 * Contents: [translate icon][source] → [target][status][preview][insert][close]
 *
 * The pill is driven entirely by [Translator]'s state flows – it contains no translation logic.
 */
class TranslatePillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val pillContainer: LinearLayout
    private val iconButton: ImageButton
    private val sourceButton: TextView
    private val arrowView: TextView
    private val targetButton: TextView
    private val progressView: ProgressBar
    private val statusView: TextView
    private val previewView: TextView
    private val insertButton: ImageButton
    private val closeButton: ImageButton

    private var scope: CoroutineScope? = null
    private var hostVisible = false
    private var shown = false
    private var languagePicker: PopupWindow? = null
    private var lastState: TranslationUiState = TranslationUiState.Idle
    private var lastAnnouncedError: String? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.translate_pill, this, true)
        pillContainer = findViewById(R.id.translate_pill_container)
        iconButton = findViewById(R.id.translate_pill_icon)
        sourceButton = findViewById(R.id.translate_pill_source)
        arrowView = findViewById(R.id.translate_pill_arrow)
        targetButton = findViewById(R.id.translate_pill_target)
        progressView = findViewById(R.id.translate_pill_progress)
        statusView = findViewById(R.id.translate_pill_status)
        previewView = findViewById(R.id.translate_pill_preview)
        insertButton = findViewById(R.id.translate_pill_insert)
        closeButton = findViewById(R.id.translate_pill_close)

        // user actions only – the pill never modifies the editor by itself
        iconButton.setOnClickListener { Translator.instance()?.requestManualTranslation() }
        sourceButton.setOnClickListener { showLanguagePicker(forSource = true) }
        targetButton.setOnClickListener { showLanguagePicker(forSource = false) }
        statusView.setOnClickListener { Translator.instance()?.toggleLiveMode() }
        insertButton.setOnClickListener { Translator.instance()?.insertTranslation(replace = false) }
        insertButton.setOnLongClickListener {
            Translator.instance()?.insertTranslation(replace = true)
            true
        }
        closeButton.setOnClickListener { Translator.instance()?.setEnabled(false) }

        insertButton.isEnabled = false
        visibility = View.GONE
    }

    /** Called by the KeyboardSwitcher when the main keyboard is (not) hosting the pill. */
    fun setHostVisible(visible: Boolean) {
        if (hostVisible == visible) return
        hostVisible = visible
        refreshVisibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateThemeColors(Settings.getValues().mColors)
        val controller = Translator.instance() ?: return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        newScope.launch { controller.state.collect { state -> lastState = state; renderState(state) } }
        newScope.launch { controller.enabled.collect { refreshVisibility() } }
        newScope.launch { controller.blocked.collect { refreshVisibility() } }
        newScope.launch { controller.liveMode.collect { renderState(lastState) } }
        newScope.launch { controller.sourceLanguage.collect { renderLanguages() } }
        newScope.launch { controller.targetLanguage.collect { renderLanguages() } }
        renderLanguages()
        renderState(Translator.currentState())
    }

    override fun onDetachedFromWindow() {
        dismissLanguagePicker()
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    /** Very light show/hide animation (fade + 6dp slide), deliberately not a heavy transition. */
    private fun refreshVisibility() {
        val controller = Translator.instance()
        val show = hostVisible && controller?.enabled?.value == true && !controller.blocked.value
        if (show == shown) return
        shown = show
        animate().cancel()
        if (show) {
            if (visibility != View.VISIBLE) {
                visibility = View.VISIBLE
                alpha = 0f
                translationY = 6f * resources.displayMetrics.density
            }
            animate().alpha(1f).translationY(0f).setDuration(140L).start()
        } else if (visibility == View.VISIBLE) {
            animate().alpha(0f).setDuration(90L).withEndAction {
                if (!shown) visibility = View.GONE
            }.start()
        }
    }

    private fun renderState(state: TranslationUiState) {
        val liveMode = Translator.instance()?.liveMode?.value == true
        when (state) {
            is TranslationUiState.Idle -> {
                progressView.visibility = View.GONE
                statusView.text = context.getString(R.string.translate_status_idle)
                previewView.text = ""
            }

            is TranslationUiState.Translating -> {
                progressView.visibility = if (state.totalChunks > 1) View.VISIBLE else View.GONE
                statusView.text = if (state.totalChunks > 1) {
                    "${state.doneChunks}/${state.totalChunks}"
                } else {
                    context.getString(R.string.translate_status_translating)
                }
                previewView.text = state.lastTranslation.orEmpty()
            }

            is TranslationUiState.Success -> {
                progressView.visibility = View.GONE
                statusView.text = context.getString(
                    if (liveMode) R.string.translate_status_live else R.string.translate_status_done
                )
                previewView.text = state.translatedText
                lastAnnouncedError = null
            }

            is TranslationUiState.Error -> {
                progressView.visibility = View.GONE
                statusView.text = state.message
                previewView.text = state.lastTranslation.orEmpty()
                if (state.message != lastAnnouncedError) {
                    lastAnnouncedError = state.message
                    announceForAccessibility(state.message)
                }
            }
        }
        insertButton.isEnabled = when (state) {
            is TranslationUiState.Success -> true
            is TranslationUiState.Translating -> !state.lastTranslation.isNullOrBlank()
            else -> false
        }
    }

    private fun renderLanguages() {
        val controller = Translator.instance() ?: return
        val source = controller.sourceLanguage.value
        val detected = (lastState as? TranslationUiState.Success)?.detectedSourceLanguage
        sourceButton.text = if (source == TranslationLanguages.AUTO_DETECT) {
            if (detected.isNullOrBlank()) {
                context.getString(R.string.translate_auto)
            } else {
                context.getString(R.string.translate_auto_detected, detected)
            }
        } else {
            source
        }
        sourceButton.contentDescription =
            context.getString(R.string.translate_source_language) + ": " + sourceButton.text
        val target = controller.targetLanguage.value
        targetButton.text = target
        targetButton.contentDescription = context.getString(R.string.translate_target_language) +
            ": " + TranslationLanguages.displayNameFor(target)
        renderState(lastState)
    }

    private fun dismissLanguagePicker() {
        languagePicker?.dismiss()
        languagePicker = null
    }

    /**
     * Compact scrollable language picker shown above the pill. Not focusable, so the IME keeps
     * receiving input; outside touches dismiss it.
     */
    private fun showLanguagePicker(forSource: Boolean) {
        val controller = Translator.instance() ?: return
        dismissLanguagePicker()
        val density = resources.displayMetrics.density
        val width = (240 * density).toInt()
        val maxHeight = (300 * density).toInt()

        val textColor = Settings.getValues().mColors.get(ColorType.KEY_TEXT)
        val rowBackground = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (6 * density).toInt(), (6 * density).toInt(),
                (6 * density).toInt(), (6 * density).toInt(),
            )
        }
        val current = if (forSource) controller.sourceLanguage.value else controller.targetLanguage.value

        fun addRow(code: String, label: String) {
            val selected = code.equals(current, ignoreCase = true)
            val row = TextView(context).apply {
                text = if (selected) "✓ $label" else label
                textSize = 14f
                setTextColor(textColor)
                gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                minHeight = (44 * density).toInt()
                isClickable = true
                setBackgroundResource(rowBackground)
                contentDescription = label
                setOnClickListener {
                    dismissLanguagePicker()
                    if (forSource) controller.setSourceLanguage(code) else controller.setTargetLanguage(code)
                }
            }
            list.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        if (forSource) {
            addRow(TranslationLanguages.AUTO_DETECT, context.getString(R.string.translate_auto_detect))
        }
        TranslationLanguages.orderedForPicker().forEach { language ->
            addRow(language.code, "${language.displayName}  (${language.code})")
        }

        val scroll = ScrollView(context).apply { addView(list) }
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = minOf(scroll.measuredHeight, maxHeight).coerceAtLeast((48 * density).toInt())

        val popup = PopupWindow(scroll, width, height, false)
        popup.isOutsideTouchable = true
        popup.isFocusable = false
        popup.elevation = 8f * density
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.setOnDismissListener { languagePicker = null }
        languagePicker = popup
        // show above the pill (negative offset lifts it over the anchor), same pattern as KlipyPalettesView
        popup.showAsDropDown(this, (4 * density).toInt(), -height - this.height)
    }

    /** Applies keyboard theme colors – called on attach and whenever the theme changes. */
    fun updateThemeColors(colors: Colors) {
        val keyText = colors.get(ColorType.KEY_TEXT)
        val keyIcon = colors.get(ColorType.KEY_ICON)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            colors.setColor(this, ColorType.KEY_BACKGROUND)
        }
        pillContainer.background = background
        sourceButton.setTextColor(keyText)
        targetButton.setTextColor(keyText)
        arrowView.setTextColor(keyText)
        statusView.setTextColor(keyText)
        previewView.setTextColor(keyText)
        iconButton.imageTintList = ColorStateList.valueOf(keyIcon)
        insertButton.imageTintList = ColorStateList.valueOf(keyIcon)
        closeButton.imageTintList = ColorStateList.valueOf(keyIcon)
        progressView.indeterminateTintList = ColorStateList.valueOf(keyText)
    }
}
