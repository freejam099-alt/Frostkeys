// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

/**
 * Small thread safe LRU cache for translations.
 *
 * Key: `sourceLanguage|targetLanguage|normalizedText` (e.g. `EN|ID|hello world`).
 * The cache is bounded, so it can never grow without limit and cause a memory leak
 * in the long lived IME process.
 *
 * The cache is only written for text that was safe to send (never secure/blocked fields);
 * all callers go through [TranslationController], which enforces that.
 */
class TranslationCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    companion object {
        const val DEFAULT_MAX_ENTRIES = 512
    }

    // accessOrder=true -> get() also refreshes recency (classic LRU)
    private val map = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > maxEntries
    }

    private val lock = Any()

    fun key(sourceLanguage: String, targetLanguage: String, text: String): String =
        "${sourceLanguage.uppercase()}|${targetLanguage.uppercase()}|${normalize(text)}"

    fun get(sourceLanguage: String, targetLanguage: String, text: String): String? =
        synchronized(lock) { map[key(sourceLanguage, targetLanguage, text)] }

    fun put(sourceLanguage: String, targetLanguage: String, text: String, translatedText: String) {
        if (text.isBlank() || translatedText.isEmpty()) return
        synchronized(lock) { map[key(sourceLanguage, targetLanguage, text)] = translatedText }
    }

    fun clear() = synchronized(lock) { map.clear() }

    val size: Int get() = synchronized(lock) { map.size }

    private fun normalize(text: String): String = text.trim()
}
