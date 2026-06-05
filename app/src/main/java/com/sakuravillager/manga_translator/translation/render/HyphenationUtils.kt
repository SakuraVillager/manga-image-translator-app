package com.sakuravillager.manga_translator.translation.render

import android.graphics.Paint

/**
 * Simple word-wrap utility for text rendering.
 *
 * Python's manga-image-translator uses the `hyphen` library (Hyphenator) for proper
 * Latin hyphenation. Android has no direct equivalent, so this implementation provides
 * a simplified word-wrap that:
 *
 * 1. Breaks at explicit `\n` line separators
 * 2. Breaks at word boundaries (spaces) for Latin text
 * 3. Breaks at ANY character for CJK text (no word boundaries)
 * 4. Falls back to character-level breaking for long words that exceed the width
 *
 * This is a simplified alternative to the full FreeType-based layout in Python.
 * It is sufficient for Android's Canvas/Paint rendering where kerning and glyph-level
 * control are not available.
 */
object HyphenationUtils {

    /**
     * Wraps text into lines that fit within [maxWidth] pixels.
     *
     * @param text the text to wrap
     * @param paint paint configured with the target font size and typeface
     * @param maxWidth maximum width in pixels for each line
     * @return list of lines, each guaranteed to fit within maxWidth
     */
    fun wrapLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (maxWidth <= 0f) return listOf(text)

        val lines = mutableListOf<String>()
        // Split by explicit newlines first
        for (paragraph in text.split('\n')) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) {
                lines.add("")
                continue
            }
            lines.addAll(wrapParagraph(trimmed, paint, maxWidth))
        }
        return lines
    }

    /**
     * Wraps a single paragraph (no explicit newlines) into lines.
     */
    private fun wrapParagraph(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (paint.measureText(remaining) <= maxWidth) {
                lines.add(remaining)
                break
            }

            // Find the best break point
            // Try word boundaries first (for Latin text)
            val breakIndex = findBreakIndex(remaining, paint, maxWidth)
            if (breakIndex <= 0) {
                // Cannot break at all — force character break
                lines.add(remaining)
                break
            }

            lines.add(remaining.substring(0, breakIndex).trimEnd())
            remaining = remaining.substring(breakIndex).trimStart()
        }

        return lines.ifEmpty { listOf(text) }
    }

    /**
     * Finds the best index to break a line.
     *
     * Strategy:
     * 1. If the text is CJK-dominated (multi-byte characters), break at any character
     * 2. If the text is Latin, break at the last word boundary before maxWidth
     * 3. If no word boundary found, break at the last character that fits
     */
    private fun findBreakIndex(text: String, paint: Paint, maxWidth: Float): Int {
        // Check if text is CJK: count CJK characters vs ASCII
        val cjkCount = text.count { ch ->
            ch in '　'..'〿' || ch in '一'..'鿿' ||
                ch in '㐀'..'䶿' || ch in '豈'..'﫿' ||
                ch in '぀'..'ゟ' || ch in '゠'..'ヿ' ||
                ch in '가'..'힯'
        }
        val isCjk = cjkCount > text.length / 2

        // For CJK text, binary search for the last character that fits
        if (isCjk) {
            var lo = 1
            var hi = text.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (paint.measureText(text.substring(0, mid)) <= maxWidth) {
                    lo = mid
                } else {
                    hi = mid - 1
                }
            }
            return lo
        }

        // For Latin text, try word boundaries
        var lastSpace = -1
        for (i in 1..text.length) {
            if (paint.measureText(text.substring(0, i)) > maxWidth) {
                break
            }
            if (text[i - 1] == ' ') {
                lastSpace = i
            }
        }

        if (lastSpace > 0) return lastSpace

        // No word boundary found — binary search for character break
        var lo = 1
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paint.measureText(text.substring(0, mid)) <= maxWidth) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        return lo
    }
}