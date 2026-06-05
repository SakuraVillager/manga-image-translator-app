package com.sakuravillager.manga_translator.translation.language

data class LanguageDetection(
    val language: String,
    val confidence: Float,
)

/**
 * Detects the source language of a text string.
 *
 * The default production implementation is [ScriptLanguageDetector], which uses Unicode script
 * heuristics (hiragana → JPN, hangul → KOR, etc.). This is adequate for typical manga scenarios
 * where the script is a strong language signal (e.g. hiragana is always Japanese).
 *
 * Future implementations may use statistical models (analogous to Python's `py3langid` / `langcodes`)
 * for better accuracy on ambiguous inputs (pure CJK text, short text, Russian vs Ukrainian, etc.).
 *
 * ## Contract
 * - [detect] must never throw — returning `LanguageDetection("UNKNOWN", 0f)` for ambiguous input
 *   is correct behavior.
 * - [isTargetLanguage] returns `true` for `"auto"` target, meaning "always translate".
 *
 * @see com.sakuravillager.manga_translator.translation.translator.common.ISO_639_1_TO_VALID_LANGUAGES
 *      for mapping ISO 639-1 codes (e.g. `"ja"` → `"JPN"`) used by external APIs.
 */
interface LanguageDetector {
    fun detect(text: String): LanguageDetection

    fun isTargetLanguage(text: String, targetLanguage: String): Boolean {
        if (text.isBlank()) return false
        if (targetLanguage.equals("auto", ignoreCase = true)) return true
        return detect(text).language.equals(targetLanguage, ignoreCase = true)
    }
}

