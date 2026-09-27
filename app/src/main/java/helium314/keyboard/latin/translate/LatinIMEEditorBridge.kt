// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

import android.widget.Toast
import helium314.keyboard.latin.LatinIME

/**
 * Thin adapter binding the Android free [TranslationController] to the IME.
 * All calls happen on the main thread; `InputConnection` is only touched from there.
 */
class LatinIMEEditorBridge(private val ime: LatinIME) : EditorBridge {

    override fun getTextBeforeCursor(maxChars: Int): CharSequence? =
        ime.currentInputConnection?.getTextBeforeCursor(maxChars, 0)

    override fun isSecureField(): Boolean {
        // when in doubt (no editor info), treat the field as sensitive and stay silent
        val editorInfo = ime.currentInputEditorInfo ?: return true
        return TranslationPrivacy.shouldDisableTranslation(editorInfo.inputType, editorInfo.imeOptions)
    }

    override fun commitText(text: String) {
        ime.currentInputConnection?.commitText(text, 1)
    }

    override fun replaceAllText(text: String) {
        val connection = ime.currentInputConnection ?: return
        connection.beginBatchEdit()
        try {
            val before = connection.getTextBeforeCursor(MAX_REPLACE_CHARS, 0)?.length ?: 0
            val after = connection.getTextAfterCursor(MAX_REPLACE_CHARS, 0)?.length ?: 0
            if (before > 0 || after > 0) connection.deleteSurroundingText(before, after)
            connection.commitText(text, 1)
        } finally {
            connection.endBatchEdit()
        }
    }

    override fun onTranslationInserted(replace: Boolean) {
        val message = if (replace) "Text replaced with translation" else "Translation inserted"
        Toast.makeText(ime, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MAX_REPLACE_CHARS = 100_000
    }
}
