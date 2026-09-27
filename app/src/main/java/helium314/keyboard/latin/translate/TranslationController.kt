// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Everything the controller needs from the IME side. Implemented by a thin adapter over
 * [helium314.keyboard.latin.LatinIME] so the controller itself stays Android free and unit testable.
 *
 * All methods are called on the main thread only.
 */
interface EditorBridge {
    /** Text before the cursor, at most [maxChars] characters. May return null from broken editors. */
    fun getTextBeforeCursor(maxChars: Int): CharSequence?

    /** True for password/OTP/other sensitive editors – text must never leave the device. */
    fun isSecureField(): Boolean

    /** Inserts [text] at the cursor (user initiated action). */
    fun commitText(text: String)

    /** Replaces the whole editor content with [text] (user initiated action). */
    fun replaceAllText(text: String)

    /** Small confirmation for the user (toast). */
    fun onTranslationInserted(replace: Boolean)
}

/** Internal signal used to abort all chunk workers as soon as one chunk fails. */
class TranslationFailedException(val failure: TranslationResult.Failure) : Exception(failure.message)

/**
 * Central brain of the live translate feature.
 *
 * Responsibilities:
 * - debounce text changes (default 250 ms) and pull the current text at most once per burst
 * - deduplicate identical work (same text, same languages)
 * - cancel the previous request as soon as new text arrives (coroutine cancellation + HTTP call cancel)
 * - strictly discard stale results (monotonic generation, checked before every UI update)
 * - chunk very long text and translate chunks with small, controlled parallelism
 * - reuse an LRU cache keyed by `source|target|text`
 * - never touch the editor unless the user explicitly inserts a translation
 *
 * Threading: public API is main-thread only. Network runs in the engine (OkHttp async),
 * chunking / string building run on [computeDispatcher].
 */
class TranslationController(
    private val engine: TranslationEngine,
    private val cache: TranslationCache = TranslationCache(),
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val maxParallelChunks: Int = DEFAULT_MAX_PARALLEL_CHUNKS,
    private val maxPullChars: Int = DEFAULT_MAX_PULL_CHARS,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 250L
        const val DEFAULT_MAX_PARALLEL_CHUNKS = 2
        const val DEFAULT_MAX_PULL_CHARS = 60_000
    }

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private val _state = MutableStateFlow<TranslationUiState>(TranslationUiState.Idle)
    val state: StateFlow<TranslationUiState> = _state.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** True while translation must not run (no active editor, or a sensitive field). */
    private val _blocked = MutableStateFlow(true)
    val blocked: StateFlow<Boolean> = _blocked.asStateFlow()

    private val _liveMode = MutableStateFlow(true)
    val liveMode: StateFlow<Boolean> = _liveMode.asStateFlow()

    private val _sourceLanguage = MutableStateFlow(TranslationLanguages.AUTO_DETECT)
    val sourceLanguage: StateFlow<String> = _sourceLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow("EN")
    val targetLanguage: StateFlow<String> = _targetLanguage.asStateFlow()

    /** Persistence hooks, wired once by [Translator]. */
    var onLanguageChanged: ((source: String, target: String) -> Unit)? = null
    var onLiveModeChanged: ((Boolean) -> Unit)? = null

    /** Monotonically increasing request id – the stale result killer. */
    private val generation = AtomicLong(0L)

    private var bridge: EditorBridge? = null
    private var debounceJob: Job? = null
    private var translateJob: Job? = null
    private var lastAnalyzedText: String? = null
    private var skipNextAnalyze = false
    private var editorActive = false

    // ---------------------------------------------------------------- public API

    fun restoreLanguages(source: String, target: String, live: Boolean) {
        if (TranslationLanguages.isValidCode(source)) _sourceLanguage.value = normalizedSource(source)
        if (TranslationLanguages.isValidCode(target) &&
            !target.equals(TranslationLanguages.AUTO_DETECT, ignoreCase = true)
        ) {
            _targetLanguage.value = target.uppercase()
        }
        _liveMode.value = live
    }

    fun attachEditor(bridge: EditorBridge?) {
        this.bridge = bridge
        if (bridge == null) {
            editorActive = false
            cancelWork()
            _state.value = TranslationUiState.Idle
        }
        refreshBlocked()
    }

    fun onStartInputView() {
        editorActive = true
        lastAnalyzedText = null
        refreshBlocked()
        if (_enabled.value && _liveMode.value && !_blocked.value) scheduleAnalyze()
    }

    fun onFinishInputView() {
        editorActive = false
        cancelWork()
        refreshBlocked()
    }

    /** Typing hook: cheap, called on every selection/text change, debounced internally. */
    fun onEditorTextMayHaveChanged() {
        if (!_enabled.value || !editorActive || !_liveMode.value) return
        scheduleAnalyze()
    }

    fun setEnabled(enabled: Boolean) {
        if (_enabled.value == enabled) return
        _enabled.value = enabled
        if (!enabled) {
            cancelWork()
            _state.value = TranslationUiState.Idle
        } else {
            refreshBlocked()
            if (_liveMode.value && !_blocked.value) scheduleAnalyze()
        }
    }

    fun toggle() = setEnabled(!_enabled.value)

    fun setTargetLanguage(code: String) {
        if (!TranslationLanguages.isValidCode(code) ||
            code.equals(TranslationLanguages.AUTO_DETECT, ignoreCase = true)
        ) {
            return
        }
        val normalized = code.uppercase()
        if (_targetLanguage.value == normalized) return
        _targetLanguage.value = normalized
        onLanguageChanged?.invoke(_sourceLanguage.value, normalized)
        retranslateAfterConfigurationChange()
    }

    fun setSourceLanguage(code: String) {
        val normalized = normalizedSource(code)
        if (!TranslationLanguages.isValidCode(normalized)) return
        if (_sourceLanguage.value == normalized) return
        _sourceLanguage.value = normalized
        onLanguageChanged?.invoke(normalized, _targetLanguage.value)
        retranslateAfterConfigurationChange()
    }

    fun setLiveMode(live: Boolean) {
        if (_liveMode.value == live) return
        _liveMode.value = live
        onLiveModeChanged?.invoke(live)
        if (!live) {
            cancelWork()
        } else if (_enabled.value) {
            lastAnalyzedText = null
            scheduleAnalyze()
        }
    }

    fun toggleLiveMode() = setLiveMode(!_liveMode.value)

    /** Manual refresh: translates the current text once, also when live mode is off. */
    fun requestManualTranslation() {
        if (!_enabled.value) return
        val currentBridge = bridge ?: return
        scope.launch {
            val text = pullText(currentBridge) ?: return@launch
            if (currentBridge.isSecureField()) {
                _blocked.value = true
                cancelWork()
                _state.value = TranslationUiState.Idle
                return@launch
            }
            if (text.isBlank()) {
                _state.value = TranslationUiState.Idle
                return@launch
            }
            lastAnalyzedText = text
            startTranslation(text)
        }
    }

    /**
     * Inserts the current translation into the editor – only ever called from an explicit user
     * action. The next automatic analysis is skipped so the feature does not translate its own output.
     */
    fun insertTranslation(replace: Boolean) {
        val currentBridge = bridge ?: return
        val translated = when (val current = _state.value) {
            is TranslationUiState.Success -> current.translatedText
            is TranslationUiState.Translating -> current.lastTranslation
            else -> null
        }
        if (translated.isNullOrBlank()) return
        skipNextAnalyze = true
        runCatching {
            if (replace) currentBridge.replaceAllText(translated) else currentBridge.commitText(translated)
        }
        currentBridge.onTranslationInserted(replace)
    }

    override fun close() {
        cancelWork()
        scope.cancel()
    }

    // ---------------------------------------------------------------- internal logic

    private fun normalizedSource(code: String): String =
        if (code.equals(TranslationLanguages.AUTO_DETECT, ignoreCase = true)) {
            TranslationLanguages.AUTO_DETECT
        } else {
            code.uppercase()
        }

    private fun retranslateAfterConfigurationChange() {
        if (!_enabled.value) return
        lastAnalyzedText = null
        if (_liveMode.value) scheduleAnalyze()
    }

    /** Cancels the pending debounce and starts a new one – classic debounce behaviour. */
    private fun scheduleAnalyze() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            analyzeCurrentText()
        }
    }

    private suspend fun analyzeCurrentText() {
        val currentBridge = bridge ?: return
        val text = pullText(currentBridge) ?: return
        if (currentBridge.isSecureField()) {
            _blocked.value = true
            cancelWork()
            _state.value = TranslationUiState.Idle
            return
        }
        if (skipNextAnalyze) {
            skipNextAnalyze = false
            lastAnalyzedText = text
            return
        }
        if (text.isBlank()) {
            lastAnalyzedText = text
            _state.value = TranslationUiState.Idle
            return
        }
        // deduplicate: cursor moves / selection updates with unchanged text do not retrigger work
        if (text == lastAnalyzedText) return
        lastAnalyzedText = text
        startTranslation(text)
    }

    private fun pullText(currentBridge: EditorBridge): String? =
        runCatching { currentBridge.getTextBeforeCursor(maxPullChars)?.toString() }.getOrNull()

    private fun startTranslation(text: String) {
        translateJob?.cancel()
        val requestGeneration = generation.incrementAndGet()
        translateJob = scope.launch {
            val source = _sourceLanguage.value
            val target = _targetLanguage.value

            cache.get(source, target, text)?.let { cached ->
                if (generation.get() == requestGeneration) {
                    _state.value = TranslationUiState.Success(text, cached, null)
                }
                return@launch
            }

            val previousTranslation = lastTranslation()
            _state.value = TranslationUiState.Translating(text, previousTranslation, 0, 0)
            try {
                val chunks = withContext(computeDispatcher) { TranslationChunker.split(text) }
                if (generation.get() != requestGeneration) return@launch
                _state.value = TranslationUiState.Translating(text, previousTranslation, 0, chunks.size)

                val results = arrayOfNulls<String>(chunks.size)
                val detectedLanguage = AtomicReference<String?>(null)
                val doneChunks = AtomicInteger(0)

                coroutineScope {
                    val permits = Semaphore(maxParallelChunks)
                    for (index in chunks.indices) {
                        launch {
                            permits.withPermit {
                                coroutineContext.ensureActive()
                                if (generation.get() != requestGeneration) return@withPermit
                                val chunk = chunks[index]
                                val translatedChunk = cache.get(source, target, chunk) ?: run {
                                    when (val result = engine.translate(chunk, source, target)) {
                                        is TranslationResult.Success -> {
                                            cache.put(source, target, chunk, result.translatedText)
                                            result.detectedSourceLanguage?.let {
                                                detectedLanguage.compareAndSet(null, it)
                                            }
                                            result.translatedText
                                        }
                                        is TranslationResult.Failure -> throw TranslationFailedException(result)
                                    }
                                }
                                results[index] = translatedChunk
                                val done = doneChunks.incrementAndGet()
                                if (generation.get() == requestGeneration) {
                                    _state.value = TranslationUiState.Translating(
                                        text,
                                        partialTranslation(results) ?: previousTranslation,
                                        done,
                                        chunks.size,
                                    )
                                }
                            }
                        }
                    }
                }

                if (generation.get() != requestGeneration) return@launch
                val combined = withContext(computeDispatcher) {
                    buildString(chunks.sumOf { it.length }) { results.forEach { append(it ?: "") } }
                }
                _state.value = TranslationUiState.Success(text, combined, detectedLanguage.get())
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationFailedException) {
                if (generation.get() == requestGeneration) {
                    _state.value = TranslationUiState.Error(e.failure.message, previousTranslation)
                }
            } catch (unexpected: Exception) {
                if (generation.get() == requestGeneration) {
                    _state.value = TranslationUiState.Error("Translation failed", previousTranslation)
                }
            }
        }
    }

    /** Contiguous translated prefix, used for progressive display while chunking. */
    private fun partialTranslation(results: Array<String?>): String? {
        val builder = StringBuilder()
        for (value in results) {
            if (value == null) break
            builder.append(value)
        }
        return builder.takeIf { it.isNotEmpty() }?.toString()
    }

    private fun lastTranslation(): String? = when (val current = _state.value) {
        is TranslationUiState.Success -> current.translatedText
        is TranslationUiState.Translating -> current.lastTranslation
        is TranslationUiState.Error -> current.lastTranslation
        TranslationUiState.Idle -> null
    }

    /** Cancels all pending work and invalidates every in-flight result via the generation counter. */
    private fun cancelWork() {
        debounceJob?.cancel()
        debounceJob = null
        translateJob?.cancel()
        translateJob = null
        generation.incrementAndGet()
    }

    private fun refreshBlocked() {
        val currentBridge = bridge
        _blocked.value = currentBridge == null || !editorActive ||
            runCatching { currentBridge.isSecureField() }.getOrDefault(true)
    }
}
