// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Privacy guard for the live translate feature.
 *
 * A keyboard sees everything the user types, so text must never leave the device
 * for fields that are (or are very likely) sensitive.
 */
object TranslationPrivacy {
    /**
     * True if live translation must be disabled for the given editor.
     *
     * Covers:
     * - password fields (text password, visible password, web password, numeric password)
     * - numeric and phone fields (also protects OTP / PIN / payment inputs, no useful translation)
     * - editors that explicitly opt out of personalized learning
     */
    fun shouldDisableTranslation(inputType: Int, imeOptions: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> return true
            }
        }
        if (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return true
        }
        if (inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE) {
            return true
        }
        if (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) {
            return true
        }
        return false
    }
}
