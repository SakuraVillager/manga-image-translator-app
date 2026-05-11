package com.sakuravillager.manga_translator.translation.pipeline

import android.util.Log

/**
 * Detects repetition-based hallucinations in translated text.
 *
 * Ported from Python's `_check_repetition_hallucination()` in manga_translator.py.
 * Three-layer detection:
 * 1. Character-level: consecutive identical characters
 * 2. Word-level: consecutive identical word segments (CJK chars + non-space tokens)
 * 3. Phrase-level: repeated phrase patterns via sliding window
 */
object RepetitionHallucinationChecker {
    private const val TAG = "RepetitionHallucinationChecker"

    /**
     * Checks if the given text exhibits repetition-based hallucination patterns.
     *
     * @param text The translated text to check.
     * @param threshold Minimum repetition count to flag as hallucination (default: 5).
     * @return true if any repetition pattern is detected, false otherwise.
     */
    fun check(text: String, threshold: Int = 5): Boolean {
        if (text.isBlank() || text.length < threshold) return false

        // 1. Character-level repetition detection
        if (checkCharacterRepetition(text, threshold)) return true

        // 2. Word-level (segment repetition) detection
        if (checkWordRepetition(text, threshold)) return true

        // 3. Phrase-level repetition detection
        if (checkPhraseRepetition(text, threshold)) return true

        return false
    }

    /**
     * Detects consecutive identical characters (e.g., "aaaaa").
     */
    private fun checkCharacterRepetition(text: String, threshold: Int): Boolean {
        var consecutiveCount = 1
        var prevChar: Char? = null
        for (char in text) {
            if (char == prevChar) {
                consecutiveCount++
                if (consecutiveCount >= threshold) {
                    Log.w(TAG, "Detected character repetition: '$char' repeated $consecutiveCount times")
                    return true
                }
            } else {
                consecutiveCount = 1
            }
            prevChar = char
        }
        return false
    }

    /**
     * Detects consecutive identical word segments.
     * Segments are defined as CJK characters (each as individual tokens) or
     * non-whitespace token sequences (matching Python's re.findall(r'[\u4e00-\u9fff]|\S+')).
     */
    private fun checkWordRepetition(text: String, threshold: Int): Boolean {
        val segments = Regex("""[\u4e00-\u9fff]|\S+""").findAll(text)
            .map { it.value }
            .toList()

        if (segments.size >= threshold) {
            var consecutiveSegments = 1
            var prevSegment: String? = null
            for (segment in segments) {
                if (segment == prevSegment) {
                    consecutiveSegments++
                    if (consecutiveSegments >= threshold) {
                        Log.w(TAG, "Detected word repetition: '$segment' repeated $consecutiveSegments times")
                        return true
                    }
                } else {
                    consecutiveSegments = 1
                }
                prevSegment = segment
            }
        }
        return false
    }

    /**
     * Detects repeated phrase patterns using a sliding window approach.
     * Splits text into words and checks if sub-phrases appear multiple times
     * in the remaining text.
     */
    private fun checkPhraseRepetition(text: String, threshold: Int): Boolean {
        val words = text.split(" ")
        if (words.size >= threshold * 2) {
            val halfThreshold = maxOf(1, threshold / 2)
            for (i in 0..words.size - threshold) {
                val phrase = words.subList(i, i + halfThreshold).joinToString(" ")
                val remaining = words.subList(i + halfThreshold, words.size).joinToString(" ")
                if (remaining.contains(phrase)) {
                    val phraseCount = text.split(phrase).size - 1
                    if (phraseCount >= 3) {
                        Log.w(TAG, "Detected phrase repetition: '$phrase' appeared $phraseCount times")
                        return true
                    }
                }
            }
        }
        return false
    }
}
