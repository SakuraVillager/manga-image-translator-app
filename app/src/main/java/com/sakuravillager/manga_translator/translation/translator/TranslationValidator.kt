package com.sakuravillager.manga_translator.translation.translator

import android.util.Log

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
