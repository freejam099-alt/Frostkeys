// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

/**
 * Splits arbitrarily long text into chunks that are safe to send to the backend.
 *
 * Design goals (see feature spec):
 * - no artificial text length limit in the UI; very long input is segmented, not rejected
 * - prefer splitting at paragraph > sentence > whitespace boundaries
 * - never split in the middle of a word if avoidable, never in the middle of a surrogate pair
 * - concatenating the chunks in order reproduces the original text exactly
 *   (chunks include their trailing separators, so the final translation can simply be joined)
 *
 * The default size is chosen below the DLX/DLX-proxy hard cap of 1500 characters per request
 * (anonymous traffic) to leave head room for multi-byte text.
 */
object TranslationChunker {
    const val MAX_CHUNK_CHARS = 1200

    private const val PARAGRAPH = '\n'
    private const val CARRIAGE_RETURN = '\r'
    private const val ZERO_WIDTH_JOINER = '\u200D'

    private val sentenceTerminators = charArrayOf(
        '.', '!', '?', '…', ';',          // latin
        '。', '！', '？', '；',            // CJK full width
        '؟', '۔',                          // arabic question mark / full stop
    )

    fun split(text: String, maxChars: Int = MAX_CHUNK_CHARS): List<String> {
        require(maxChars >= 16) { "maxChars must be at least 16" }
        if (text.length <= maxChars) return listOf(text)

        val chunks = ArrayList<String>(text.length / maxChars + 1)
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChars, text.length)
            if (end < text.length) {
                end = findSplitPoint(text, start, end)
            }
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }

    /**
     * Finds the best boundary in `(start, hardEnd]`, never returning something <= start
     * (progress guarantee) and preferring natural boundaries wherever they exist.
     */
    private fun findSplitPoint(text: String, start: Int, hardEnd: Int): Int {
        // only look back over the second half of the chunk, so chunks do not become tiny
        val searchFrom = maxOf(start + 1, start + (hardEnd - start) / 2)

        // 1) paragraph break ("\n\n" or "\r\n\r\n")
        var i = hardEnd
        while (i > searchFrom) {
            if (text[i - 1] == PARAGRAPH && i - 2 >= start && (text[i - 2] == PARAGRAPH || text[i - 2] == CARRIAGE_RETURN)) return i
            i--
        }

        // 2) single newline
        i = hardEnd
        while (i > searchFrom) {
            if (text[i - 1] == PARAGRAPH || text[i - 1] == CARRIAGE_RETURN) return i
            i--
        }

        // 3) sentence end (terminator stays in the current chunk)
        i = hardEnd
        while (i > searchFrom) {
            if (text[i - 1] in sentenceTerminators) return i
            i--
        }

        // 4) any whitespace
        i = hardEnd
        while (i > searchFrom) {
            if (text[i - 1].isWhitespace()) return i
            i--
        }

        // 5) hard cut, adjusted to not split surrogate pairs / CRLF / ZWJ sequences
        return safeCutIndex(text, hardEnd, start)
    }

    private fun safeCutIndex(text: String, index: Int, start: Int): Int {
        var cut = index
        while (cut > start + 1) {
            val previous = text[cut - 1]
            val next = text.getOrNull(cut)
            val splitsSurrogatePair = previous.isHighSurrogate() && next != null && next.isLowSurrogate()
            val splitsCrLf = previous == CARRIAGE_RETURN && next == PARAGRAPH
            val splitsJoinerSequence = next == ZERO_WIDTH_JOINER
            if (!splitsSurrogatePair && !splitsCrLf && !splitsJoinerSequence) break
            cut--
        }
        return cut
    }
}
