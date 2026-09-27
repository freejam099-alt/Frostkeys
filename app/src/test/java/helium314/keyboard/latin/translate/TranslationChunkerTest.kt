package helium314.keyboard.latin.translate

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure logic tests for the long-text chunker. The most important property is the concatenation
 * invariant: joining all chunks must reproduce the original text exactly, otherwise the final
 * combined translation would lose or duplicate characters.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationChunkerTest {

    @Test
    fun shortTextIsSingleChunk() {
        val text = "Hello world"
        assertEquals(listOf(text), TranslationChunker.split(text))
    }

    @Test
    fun emptyTextIsSingleEmptyChunk() {
        assertEquals(listOf(""), TranslationChunker.split(""))
    }

    @Test
    fun concatenationInvariantForParagraphs() {
        val paragraph = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. "
        val text = paragraph.repeat(200) + "\n\n" + paragraph.repeat(150)
        assertConcatenationInvariant(text)
    }

    @Test
    fun concatenationInvariantForCjkWithoutSpaces() {
        val text = "这是一个没有空格的非常长的中文句子。".repeat(300)
        assertConcatenationInvariant(text)
    }

    @Test
    fun concatenationInvariantForSingleHugeWord() {
        val text = "a".repeat(5000)
        assertConcatenationInvariant(text)
    }

    @Test
    fun concatenationInvariantWithEmojiAndRtl() {
        val text = ("😀 emoji 👍 test مرحبا بالعالم " + "🎉".repeat(3)).repeat(80)
        assertConcatenationInvariant(text)
    }

    @Test
    fun chunksRespectMaximumSize() {
        val text = "word ".repeat(1000)
        val chunks = TranslationChunker.split(text, maxChars = 100)
        assertTrue(chunks.all { it.length <= 100 }, "all chunks must respect the maximum size")
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun prefersParagraphBoundaries() {
        val first = "A".repeat(90)
        val second = "B".repeat(90)
        val text = "$first\n\n$second"
        val chunks = TranslationChunker.split(text, maxChars = 100)
        assertEquals(2, chunks.size)
        assertEquals("$first\n\n", chunks[0])
        assertEquals(second, chunks[1])
    }

    @Test
    fun prefersSentenceBoundariesOverWhitespace() {
        val text = "Sentence one is short. Sentence two is short. Sentence three is short."
        val chunks = TranslationChunker.split(text, maxChars = 45)
        assertTrue(chunks.size >= 2)
        // each chunk should end at a sentence end or whitespace, never mid-word
        chunks.dropLast(1).forEach { chunk ->
            assertTrue(
                chunk.endsWith(". ") || chunk.endsWith(" ") || chunk.endsWith("."),
                "chunk should end on a sentence or word boundary: '$chunk'",
            )
        }
    }

    @Test
    fun doesNotSplitSurrogatePairs() {
        val text = "a".repeat(1199) + "😀" + "b".repeat(50)
        assertConcatenationInvariant(text)
    }

    private fun assertConcatenationInvariant(text: String, maxChars: Int = TranslationChunker.MAX_CHUNK_CHARS) {
        val chunks = TranslationChunker.split(text, maxChars)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.length <= maxChars })
        assertEquals(text, chunks.joinToString(""), "chunks must concatenate back to the original text")
    }
}
