package com.sakuravillager.manga_translator.translation.translator

import android.util.Log
import com.sakuravillager.manga_translator.translation.translator.common.TextUtils

object TranslationValidator {
    private const val TAG = "TranslationValidator"

    // Unicode ranges for language detection
    private val CJK_RANGE = Regex("[\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff]")
    private val HIRAGANA_RANGE = Regex("[\u3040-\u309f]")
    private val KATAKANA_RANGE = Regex("[\u30a0-\u30ff]")
    private val LATIN_RANGE = Regex("[a-zA-Z]")
    private val KOREAN_RANGE = Regex("[\uac00-\ud7af\u1100-\u11ff]")

    /**
     * Detects if text has long sequences of repeated characters (hallucination).
     */
    fun hasRepetition(text: String, threshold: Int = 20): Boolean {
        if (text.length < threshold) return false

        var count = 1
        for (i in 1 until text.length) {
            if (text[i] == text[i - 1]) {
                count++
                if (count >= threshold) return true
            } else {
                count = 1
            }
        }
        return false
    }

    /**
     * Checks if the translation has too few unique symbols (hallucination indicator).
     * Matches Python's _is_translation_invalid() logic.
     */
    fun hasLowUniqueSymbolRatio(text: String, querySymbolCount: Int, threshold: Float = 0.25f): Boolean {
        if (text.length < 6) return false
        val valuableCount = TextUtils.countValuableText(text)
        return valuableCount < 6 && valuableCount < threshold * text.length
    }

    /**
     * Cleans translation output by applying regex-based post-processing rules.
     * Matches Python's _clean_translation_output() logic.
     */
    fun cleanTranslation(translation: String, query: String = "", targetLang: String = "ENG"): String {
        return try {
            var result = translation

            // Rule 1: Collapse multiple whitespace
            result = Regex("\\s+").replace(result, " ")

            // Rule 2: Add space after punctuation before a word character
            result = Regex("(?<![.,;!?])([.,;!?])(?=\\w)").replace(result, "$1 ")

            // Rule 3: Remove space between consecutive punctuation
            result = Regex("([.,;!?])\\s+(?=[.,;!?]|$)").replace(result, "$1")

            if (targetLang != "ARA") {
                // Rule 4: Remove space before trailing punctuation
                result = Regex("(?<=[.,;!?\\w])\\s+([.,;!?])").replace(result, "$1")

                // Rule 5: Remove space after ellipsis before a word ('... text' → '...text')
                result = Regex("((?:\\s|^)\\.+)\\s+(?=\\w)").replace(result, "$1")
            }

            // Rule 6: Shrink repeating sequences (only when translation is shorter than query)
            if (query.isNotEmpty() && result.length < query.length) {
                val seq = findShortestRepeatingUnit(result)
                if (seq != null && seq.length < 0.5f * result.length) {
                    val repeatCount = maxOf(1, query.length / seq.length)
                    val shrunken = seq.repeat(repeatCount)
                    // Transfer capitalization from query
                    result = if (query.firstOrNull()?.isUpperCase() == true) {
                        shrunken.replaceFirstChar { it.uppercase() }
                    } else {
                        shrunken
                    }
                }
            }

            result
        } catch (e: Exception) {
            // On any error, return original string unchanged
            translation
        }
    }

    /**
     * Finds the shortest repeating unit in text (e.g., "ab" for "ababab").
     * Returns null if no repeating unit is found.
     */
    private fun findShortestRepeatingUnit(text: String): String? {
        if (text.isEmpty()) return null
        for (len in 1..text.length / 2) {
            if (text.length % len != 0) continue
            val unit = text.substring(0, len)
            if (unit.repeat(text.length / len) == text) {
                return unit
            }
        }
        return null
    }

    /**
     * Checks if the text contains a sufficient ratio of target language characters.
     */
    fun isTargetLanguageRatio(text: String, targetLang: String, threshold: Float = 0.5f): Boolean {
        if (text.isBlank()) return false

        val targetCharCount = countTargetLanguageChars(text, targetLang)
        val ratio = targetCharCount.toFloat() / text.length.toFloat()
        Log.d(TAG, "Target language ratio for '$targetLang': $ratio (threshold: $threshold)")
        return ratio >= threshold
    }

    /**
     * Comprehensive validation of translation quality.
     * Returns true if the translation is valid, false if it should be discarded.
     */
    fun validate(
        original: String,
        translation: String,
        targetLang: String,
        repetitionThreshold: Int = 20,
        targetLangThreshold: Float = 0.5f,
    ): Boolean {
        // Skip language ratio check when target is auto
        val effectiveTargetLang = if (targetLang.lowercase() == "auto") "skip" else targetLang

        // Empty translation is invalid
        if (translation.isBlank()) {
            Log.w(TAG, "Validation failed: empty translation")
            return false
        }

        // Translation same as original (not translated) is invalid
        if (translation == original && original.isNotBlank()) {
            Log.w(TAG, "Validation failed: translation unchanged from original")
            return false
        }

        // Repetition detection
        if (hasRepetition(translation, repetitionThreshold)) {
            Log.w(TAG, "Validation failed: repetition detected in translation")
            return false
        }

        // Low unique symbol ratio detection (matches Python's _is_translation_invalid)
        if (hasLowUniqueSymbolRatio(translation, original.toSet().size)) {
            Log.w(TAG, "Validation failed: low unique symbol ratio in translation")
            return false
        }

        // Short text: if translation changed, skip language ratio (likely proper noun/etc)
        val isShortText = original.length < 5
        if (isShortText && translation != original) {
            Log.d(TAG, "Short text detected, skipping language ratio check")
            return true
        }

        // Target language ratio check
        if (!isTargetLanguageRatio(translation, effectiveTargetLang, targetLangThreshold)) {
            Log.w(TAG, "Validation failed: insufficient target language content")
            return false
        }

        return true
    }

    private fun countTargetLanguageChars(text: String, targetLang: String): Int {
        return when (targetLang.lowercase()) {
            "chs", "cht" -> text.count { CJK_RANGE.matches(it.toString()) }
            "jpn" -> text.count {
                CJK_RANGE.matches(it.toString()) ||
                HIRAGANA_RANGE.matches(it.toString()) ||
                KATAKANA_RANGE.matches(it.toString())
            }
            "kor" -> text.count { KOREAN_RANGE.matches(it.toString()) }
            "eng", "csy", "nld", "fra", "deu", "hun", "ita", "plk", "ptb", "rom", "rus", "esp", "trk", "ukr", "vin" ->
                text.count { LATIN_RANGE.matches(it.toString()) }
            "ara" -> text.count { it in '\u0600'..'\u06ff' || it in '\u0750'..'\u077f' }
            "ind" -> text.count { LATIN_RANGE.matches(it.toString()) }
            else -> text.length  // Unknown language, assume valid
        }
    }
}
