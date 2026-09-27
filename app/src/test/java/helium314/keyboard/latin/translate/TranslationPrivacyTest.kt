package helium314.keyboard.latin.translate

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Privacy guard: text must never leave the device for sensitive fields. */
@RunWith(RobolectricTestRunner::class)
class TranslationPrivacyTest {

    @Test
    fun passwordFieldsAreBlocked() {
        assertTrue(blocked(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(blocked(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(blocked(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(blocked(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun numericAndPhoneFieldsAreBlocked() {
        assertTrue(blocked(InputType.TYPE_CLASS_NUMBER))
        assertTrue(blocked(InputType.TYPE_CLASS_PHONE))
    }

    @Test
    fun regularTextIsAllowed() {
        assertFalse(blocked(InputType.TYPE_CLASS_TEXT))
        assertFalse(blocked(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(blocked(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT))
    }

    @Test
    fun noPersonalizedLearningBlocksTranslation() {
        assertTrue(
            TranslationPrivacy.shouldDisableTranslation(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            )
        )
    }

    private fun blocked(inputType: Int) =
        TranslationPrivacy.shouldDisableTranslation(inputType, imeOptions = 0)
}
