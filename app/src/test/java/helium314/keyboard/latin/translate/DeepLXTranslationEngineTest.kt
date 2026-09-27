package helium314.keyboard.latin.translate

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DeepLXTranslationEngineTest {

    // ------------------------------------------------------------ response parsing

    @Test
    fun parsesDlxSuccessResponse() {
        val parsed = parseTranslationBody(
            """{"code":200,"id":123,"data":"Halo dunia","source_lang":"EN","alternatives":[]}"""
        )
        assertEquals("Halo dunia", parsed.translatedText)
        assertEquals("EN", parsed.detectedSourceLanguage)
        assertNull(parsed.errorMessage)
    }

    @Test
    fun parsesOfficialDeepLResponse() {
        val parsed = parseTranslationBody(
            """{"translations":[{"detected_source_language":"EN","text":"Halo"}]}"""
        )
        assertEquals("Halo", parsed.translatedText)
        assertEquals("EN", parsed.detectedSourceLanguage)
    }

    @Test
    fun parsesErrorMessage() {
        val parsed = parseTranslationBody(
            """{"code":429,"message":"too many requests"}"""
        )
        assertNull(parsed.translatedText)
        assertEquals("too many requests", parsed.errorMessage)
        assertEquals(429, parsed.code)
    }

    @Test
    fun malformedAndEmptyBodiesAreTolerated() {
        assertNull(parseTranslationBody(null).translatedText)
        assertNull(parseTranslationBody("").translatedText)
        assertNull(parseTranslationBody("not json at all").translatedText)
        assertNull(parseTranslationBody("{broken").translatedText)
    }

    // ------------------------------------------------------------ endpoint normalization

    @Test
    fun endpointNormalization() {
        assertEquals("", normalizeEndpoint("   "))
        assertEquals(
            "http://127.0.0.1:1188/translate",
            normalizeEndpoint("http://127.0.0.1:1188"),
        )
        assertEquals(
            "http://127.0.0.1:1188/translate",
            normalizeEndpoint("  http://127.0.0.1:1188/  "),
        )
        assertEquals(
            "https://api-free.deepl.com/v2/translate",
            normalizeEndpoint("https://api-free.deepl.com/v2/translate"),
        )
        assertEquals(
            "https://example.com/custom/translate",
            normalizeEndpoint("https://example.com/custom/translate"),
        )
    }

    // ------------------------------------------------------------ early exit paths (no network)

    @Test
    fun emptyEndpointFailsWithoutNetwork() = runBlocking {
        val engine = DeepLXTranslationEngine(endpointProvider = { "" })
        val result = engine.translate("hello", "EN", "ID")
        assertTrue(result is TranslationResult.Failure)
        assertEquals(TranslationFailureKind.NOT_CONFIGURED, (result as TranslationResult.Failure).kind)
    }

    @Test
    fun disabledCloudGateFailsWithoutNetwork() = runBlocking {
        val engine = DeepLXTranslationEngine(
            endpointProvider = { "http://127.0.0.1:1188" },
            cloudEnabledProvider = { false },
        )
        val result = engine.translate("hello", "EN", "ID")
        assertTrue(result is TranslationResult.Failure)
        assertEquals(TranslationFailureKind.CLOUD_DISABLED, (result as TranslationResult.Failure).kind)
    }

    @Test
    fun emptyInputReturnsEmptySuccess() = runBlocking {
        val engine = DeepLXTranslationEngine(endpointProvider = { "http://127.0.0.1:1188" })
        val result = engine.translate("", "EN", "ID")
        assertTrue(result is TranslationResult.Success)
        assertEquals("", (result as TranslationResult.Success).translatedText)
    }
}
