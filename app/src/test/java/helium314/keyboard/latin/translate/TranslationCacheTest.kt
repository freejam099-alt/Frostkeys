package helium314.keyboard.latin.translate

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TranslationCacheTest {

    @Test
    fun cacheHitAndMiss() {
        val cache = TranslationCache()
        assertNull(cache.get("EN", "ID", "hello world"))
        cache.put("EN", "ID", "hello world", "halo dunia")
        assertEquals("halo dunia", cache.get("EN", "ID", "hello world"))
    }

    @Test
    fun keyIsNormalizedButTextSensitive() {
        val cache = TranslationCache()
        cache.put("en", "id", "  hello  ", "halo")
        // language codes are case insensitive, text is trimmed
        assertEquals("halo", cache.get("EN", "ID", "hello"))
        assertNull(cache.get("EN", "ID", "hello there"))
        assertNull(cache.get("EN", "DE", "hello"))
    }

    @Test
    fun blankInputsAreNeverCached() {
        val cache = TranslationCache()
        cache.put("EN", "ID", "   ", "x")
        cache.put("EN", "ID", "text", "")
        assertEquals(0, cache.size)
    }

    @Test
    fun cacheIsBoundedAndEvictsLeastRecentlyUsed() {
        val cache = TranslationCache(maxEntries = 3)
        cache.put("EN", "ID", "one", "1")
        cache.put("EN", "ID", "two", "2")
        cache.put("EN", "ID", "three", "3")
        // touch "one" so "two" becomes the least recently used
        assertEquals("1", cache.get("EN", "ID", "one"))
        cache.put("EN", "ID", "four", "4")

        assertTrue(cache.size <= 3)
        assertEquals("1", cache.get("EN", "ID", "one"))
        assertEquals("3", cache.get("EN", "ID", "three"))
        assertNull(cache.get("EN", "ID", "two"))
    }
}
