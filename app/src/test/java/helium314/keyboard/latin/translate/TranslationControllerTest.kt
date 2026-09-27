package helium314.keyboard.latin.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behaviour tests for [TranslationController]: debouncing, cancellation, stale protection,
 * backspace synchronisation, caching, privacy and long text handling.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationControllerTest {

    private var controller: TranslationController? = null

    @After
    fun tearDown() {
        controller?.close()
        controller = null
    }

    // ------------------------------------------------------------ basic flows

    @Test
    fun translatesTypedTextAfterDebounce() {
        val bridge = FakeBridge(text = "How are you")
        val engine = FakeEngine()
        val controller = start(engine, bridge)

        awaitUntil { controller.state.value is TranslationUiState.Success }
        val state = controller.state.value as TranslationUiState.Success
        assertEquals("How are you", state.sourceText)
        assertEquals("[How are you->EN]", state.translatedText)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun backspaceUpdatesTranslationWithoutShowingStaleText() {
        val bridge = FakeBridge(text = "Hello world")
        val engine = FakeEngine()
        val controller = start(engine, bridge)
        awaitUntil { success(controller)?.sourceText == "Hello world" }

        bridge.text = "Hello worl"
        controller.onEditorTextMayHaveChanged()
        awaitUntil { success(controller)?.sourceText == "Hello worl" }

        bridge.text = "Hello wor"
        controller.onEditorTextMayHaveChanged()
        awaitUntil { success(controller)?.sourceText == "Hello wor" }

        assertEquals("[Hello wor->EN]", success(controller)?.translatedText)
    }

    @Test
    fun rapidTypingDoesNotTriggerOneRequestPerCharacter() {
        val bridge = FakeBridge(text = "")
        val engine = FakeEngine()
        // longer debounce than the other tests so a mid-typing pause cannot trigger an extra request
        val controller = start(engine, bridge, debounceMs = 100)

        val typed = StringBuilder()
        repeat(20) { index ->
            typed.append(('a' + index % 26))
            bridge.text = typed.toString()
            controller.onEditorTextMayHaveChanged()
        }

        awaitUntil {
            val state = controller.state.value
            state is TranslationUiState.Success && state.sourceText == typed.toString()
        }
        assertTrue(engine.calls.size <= 2, "typing must be debounced, got ${engine.calls.size} requests")
    }

    @Test
    fun emptyTextGoesIdleWithoutRequests() {
        val bridge = FakeBridge(text = "")
        val engine = FakeEngine()
        val controller = start(engine, bridge)
        controller.onEditorTextMayHaveChanged()
        Thread.sleep(120)

        assertEquals(TranslationUiState.Idle, controller.state.value)
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun failureIsSurfacedAndRecoversOnNextTyping() {
        val bridge = FakeBridge(text = "Hello")
        val engine = FakeEngine(initialBehavior = { _, _, _ ->
            TranslationResult.Failure(TranslationFailureKind.NETWORK, "Network error")
        })
        val controller = start(engine, bridge)

        awaitUntil { controller.state.value is TranslationUiState.Error }
        assertEquals("Network error", (controller.state.value as TranslationUiState.Error).message)

        engine.behavior = { text, _, target -> TranslationResult.Success("[$text->$target]") }
        bridge.text = "Hello again"
        controller.onEditorTextMayHaveChanged()
        awaitUntil { success(controller)?.sourceText == "Hello again" }
    }

    @Test
    fun togglingOffCancelsWorkAndClearsState() {
        val bridge = FakeBridge(text = "Hello")
        val engine = FakeEngine()
        engine.delayMs = 150
        val controller = start(engine, bridge)

        awaitUntil { engine.calls.size == 1 }
        controller.setEnabled(false)
        assertEquals(TranslationUiState.Idle, controller.state.value)

        Thread.sleep(400)
        assertEquals(TranslationUiState.Idle, controller.state.value, "late result must not be applied")
    }

    @Test
    fun secureFieldBlocksAllNetworkWork() {
        val bridge = FakeBridge(text = "secret password", secure = true)
        val engine = FakeEngine()
        val controller = start(engine, bridge)
        controller.onEditorTextMayHaveChanged()
        Thread.sleep(150)

        assertTrue(controller.blocked.value)
        assertTrue(engine.calls.isEmpty(), "no text may leave the device for secure fields")
        assertEquals(TranslationUiState.Idle, controller.state.value)
    }

    @Test
    fun cacheHitAvoidsSecondEngineCall() {
        val bridge = FakeBridge(text = "Hello world")
        val engine = FakeEngine()
        val controller = start(engine, bridge)
        awaitUntil { success(controller)?.sourceText == "Hello world" }
        assertEquals(1, engine.calls.size)

        controller.requestManualTranslation()
        Thread.sleep(150)
        assertEquals(1, engine.calls.size, "identical text must be served from cache")
        assertEquals("[Hello world->EN]", success(controller)?.translatedText)
    }

    @Test
    fun changingTargetLanguageRetranslates() {
        val bridge = FakeBridge(text = "Hello")
        val engine = FakeEngine()
        val controller = start(engine, bridge)
        awaitUntil { success(controller)?.sourceText == "Hello" }

        controller.setTargetLanguage("ID")
        awaitUntil { engine.calls.any { it.third == "ID" } }
        awaitUntil { success(controller)?.translatedText == "[Hello->ID]" }
    }

    @Test
    fun liveModeOffRequiresManualTranslation() {
        val bridge = FakeBridge(text = "Hello")
        val engine = FakeEngine()
        val controller = start(engine, bridge)
        awaitUntil { success(controller)?.sourceText == "Hello" }

        controller.setLiveMode(false)
        bridge.text = "Hello there"
        controller.onEditorTextMayHaveChanged()
        Thread.sleep(150)
        assertEquals(1, engine.calls.size, "live mode off must not auto-translate")

        controller.requestManualTranslation()
        awaitUntil { success(controller)?.sourceText == "Hello there" }
    }

    @Test
    fun olderResponseNeverOverridesNewerText() {
        val bridge = FakeBridge(text = "How are")
        val engine = FakeEngine()
        engine.gateText = "How are"
        val firstGate = CompletableDeferred<Unit>()
        engine.gate = firstGate
        // rogue engine that ignores cancellation and finishes late
        engine.ignoreCancellation = true
        val controller = start(engine, bridge)
        awaitUntil { engine.calls.any { it.first == "How are" } }

        bridge.text = "How are you"
        controller.onEditorTextMayHaveChanged()
        awaitUntil { success(controller)?.sourceText == "How are you" }

        firstGate.complete(Unit)
        Thread.sleep(200)
        assertEquals("How are you", success(controller)?.sourceText)
        assertEquals("[How are you->EN]", success(controller)?.translatedText)
    }

    @Test
    fun longTextIsChunkedAndCombinedInOrder() {
        val longText = buildString { repeat(200) { append("Sentence $it is here. ") } }
        assertTrue(longText.length > TranslationChunker.MAX_CHUNK_CHARS)
        val bridge = FakeBridge(text = longText)
        val engine = FakeEngine()
        val controller = start(engine, bridge)

        awaitUntil(timeoutMs = 10_000) { success(controller)?.sourceText == longText }
        val chunks = TranslationChunker.split(longText)
        assertTrue(chunks.size > 1)
        assertTrue(engine.calls.all { it.first.length <= TranslationChunker.MAX_CHUNK_CHARS })
        assertEquals(chunks.size, engine.calls.size)
        assertEquals(chunks.joinToString("") { "[$it->EN]" }, success(controller)?.translatedText)
    }

    @Test
    fun unicodeAndEmojiAreHandledAndDetectedLanguageIsReported() {
        val bridge = FakeBridge(text = "😀 café 漢字")
        val engine = FakeEngine(initialBehavior = { text, _, _ -> TranslationResult.Success("«$text»", "EN") })
        val controller = start(engine, bridge)

        awaitUntil { success(controller) != null }
        assertEquals("«😀 café 漢字»", success(controller)?.translatedText)
        assertEquals("EN", success(controller)?.detectedSourceLanguage)
    }

    @Test
    fun insertIsExplicitAndSkipsNextAutomaticAnalysis() {
        val bridge = FakeBridge(text = "Hello")
        val engine = FakeEngine()
        val controller = start(engine, bridge)
        awaitUntil { success(controller)?.sourceText == "Hello" }
        val translation = success(controller)!!.translatedText

        controller.insertTranslation(replace = false)
        assertEquals(listOf(translation), bridge.committed.toList())

        controller.insertTranslation(replace = true)
        assertEquals(listOf(translation), bridge.replaced.toList())

        // the editor now contains the translation – it must not be translated again automatically
        val callsBefore = engine.calls.size
        bridge.text = translation
        controller.onEditorTextMayHaveChanged()
        Thread.sleep(150)
        assertEquals(callsBefore, engine.calls.size)
    }

    // ------------------------------------------------------------ helpers
    private fun success(controller: TranslationController): TranslationUiState.Success? =
        controller.state.value as? TranslationUiState.Success

    private fun start(
        engine: TranslationEngine,
        bridge: FakeBridge,
        debounceMs: Long = 30,
        cache: TranslationCache = TranslationCache(),
        enable: Boolean = true,
    ): TranslationController {
        val created = TranslationController(
            engine = engine,
            cache = cache,
            debounceMs = debounceMs,
            maxParallelChunks = 2,
            mainDispatcher = Dispatchers.Unconfined,
            computeDispatcher = Dispatchers.Unconfined,
        )
        controller = created
        created.attachEditor(bridge)
        created.onStartInputView()
        if (enable) created.setEnabled(true)
        return created
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    private class FakeBridge(var text: String = "", var secure: Boolean = false) : EditorBridge {
        val committed = CopyOnWriteArrayList<String>()
        val replaced = CopyOnWriteArrayList<String>()

        override fun getTextBeforeCursor(maxChars: Int): CharSequence? = text.take(maxChars)
        override fun isSecureField(): Boolean = secure

        override fun commitText(text: String) {
            committed.add(text)
        }

        override fun replaceAllText(text: String) {
            replaced.add(text)
        }

        override fun onTranslationInserted(replace: Boolean) {}
    }

    private class FakeEngine(
        initialBehavior: (text: String, source: String, target: String) -> TranslationResult =
            { text, _, target -> TranslationResult.Success("[$text->$target]") },
    ) : TranslationEngine {
        @Volatile
        var behavior: (text: String, source: String, target: String) -> TranslationResult = initialBehavior

        val calls = CopyOnWriteArrayList<Triple<String, String, String>>()

        @Volatile var delayMs: Long = 0

        @Volatile var gate: CompletableDeferred<Unit>? = null

        /** when set, only calls translating exactly this text are held back by [gate]. */
        @Volatile var gateText: String? = null

        @Volatile var ignoreCancellation: Boolean = false

        override suspend fun translate(
            text: String,
            sourceLanguage: String,
            targetLanguage: String,
        ): TranslationResult {
            calls.add(Triple(text, sourceLanguage, targetLanguage))
            if (delayMs > 0) delay(delayMs)
            val currentGate = if (gateText == null || gateText == text) gate else null
            if (currentGate != null) {
                if (ignoreCancellation) {
                    withContext(NonCancellable) { currentGate.await() }
                } else {
                    currentGate.await()
                }
            }
            return behavior(text, sourceLanguage, targetLanguage)
        }
    }
}
