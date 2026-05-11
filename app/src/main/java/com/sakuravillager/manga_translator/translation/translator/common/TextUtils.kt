package com.sakuravillager.manga_translator.translation.translator.common

/**
 * Utility functions for evaluating text value, ported from Python's
 * `is_valuable_char`, `count_valuable_text`, and `is_valuable_text`.
 */
object TextUtils {

    /**
     * Checks whether `ch` is a punctuation character, matching Python's
     * `unicodedata.category(c).startswith('P')`.
     */
    fun isPunctuation(ch: Char): Boolean {
        val type = Character.getType(ch).toByte()
        return type == Character.CONNECTOR_PUNCTUATION ||
                type == Character.DASH_PUNCTUATION ||
                type == Character.START_PUNCTUATION ||
                type == Character.END_PUNCTUATION ||
                type == Character.INITIAL_QUOTE_PUNCTUATION ||
                type == Character.FINAL_QUOTE_PUNCTUATION ||
                type == Character.OTHER_PUNCTUATION
    }

    /**
     * Returns `true` if [ch] is a "valuable" character.
     *
     * A character is considered valuable when it is NOT:
     * - punctuation
     * - a control / format / private-use / surrogate / unassigned character
     * - whitespace
     * - a digit
     *
     * This matches Python's `is_valuable_char`.
     */
    fun isValuableChar(ch: Char): Boolean {
        // Exclude punctuation
        if (isPunctuation(ch)) return false

        // Exclude control, format, private-use, surrogate, unassigned (Unicode C* category)
        val type = Character.getType(ch).toByte()
        if (type == Character.CONTROL ||
            type == Character.FORMAT ||
            type == Character.PRIVATE_USE ||
            type == Character.SURROGATE ||
            type == Character.UNASSIGNED
        ) return false

        // Exclude whitespace
        if (ch.isWhitespace()) return false

        // Exclude digits
        if (ch.isDigit()) return false

        return true
    }

    /**
     * Counts the number of valuable characters in [text].
     * Matches Python's `count_valuable_text`.
     */
    fun countValuableText(text: String): Int {
        return text.count { isValuableChar(it) }
    }

    /**
     * Returns `true` if [text] contains at least one valuable character.
     * Matches Python's `is_valuable_text`.
     */
    fun isValuableText(text: String): Boolean {
        return text.any { isValuableChar(it) }
    }
}
