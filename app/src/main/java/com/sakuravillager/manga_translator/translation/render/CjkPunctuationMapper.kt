package com.sakuravillager.manga_translator.translation.render

/**
 * CJK punctuation mapping between horizontal and vertical forms.
 *
 * Ported from Python `text_render.py` `CJK_H2V` and `CJK_V2H` dicts.
 * Used by [HorizontalTextRenderer] to display CJK punctuation correctly
 * when text is rendered vertically (top-to-bottom, right-to-left).
 *
 * ## Contract
 * - [translateForDirection] returns the mapped character for the target direction.
 * - Direction: `0` = horizontal, `1` = vertical.
 * - Characters not in either map pass through unchanged.
 * - The special case `ー` in vertical text should ideally be rotated 90°, but
 *   Android Canvas doesn't support per-glyph rotation. We pass it through unchanged.
 *
 * ## Note
 * Python also has `compact_special_symbols()` which replaces `...` → `…`.
 * This is not implemented here; the Kotlin `TranslationValidator.cleanTranslation()`
 * handles similar normalization.
 *
 * @see <a href="https://en.wikipedia.org/wiki/CJK_Symbols_and_Punctuation">CJK Symbols and Punctuation</a>
 */
object CjkPunctuationMapper {

    /**
     * Maps horizontal CJK punctuation to vertical forms.
     * Direct port from Python `CJK_H2V` dict in `text_render.py:22-109`.
     */
    val horizontalToVertical: Map<String, String> = mapOf(
        "‥" to "︰",
        "—" to "︱",
        "–" to "︲",
        "_" to "︴",
        "(" to "︵",
        ")" to "︶",
        "（" to "︵",
        "）" to "︶",
        "{" to "︷",
        "}" to "︸",
        "〔" to "︹",
        "〕" to "︺",
        "【" to "︻",
        "】" to "︼",
        "《" to "︽",
        "》" to "︾",
        "〈" to "︿",
        "〉" to "﹀",
        "⟨" to "︿",
        "⟩" to "﹀",
        "⟪" to "︿",
        "⟫" to "﹀",
        "「" to "﹁",
        "」" to "﹂",
        "『" to "﹃",
        "』" to "﹄",
        "﹑" to "﹅",
        "﹆" to "﹆",
        "[" to "﹇",
        "]" to "﹈",
        "⦅" to "︵",
        "⦆" to "︶",
        "❨" to "︵",
        "❩" to "︶",
        "❪" to "︷",
        "❫" to "︸",
        "❬" to "﹇",
        "❭" to "﹈",
        "❮" to "︿",
        "❯" to "﹀",
        "﹉" to "﹉",
        "﹊" to "﹊",
        "﹋" to "﹋",
        "﹌" to "﹌",
        "﹍" to "﹍",
        "﹎" to "﹎",
        "﹏" to "﹏",
        "…" to "⋮",
        "⋯" to "︙",
        "⋰" to "⋮",
        "⋱" to "⋮",
        "“" to "﹁",  // "
        "”" to "﹂",  // "
        "‘" to "﹁",  // '
        "’" to "﹂",  // '
        "″" to "﹂",
        "‴" to "﹂",
        "‶" to "﹁",
        "‷" to "﹁",
        "~" to "︴",
        "〜" to "︴",
        "～" to "︴",
        "〰" to "︴",
        "!" to "︕",
        "?" to "︖",
        "؟" to "︖",
        "¿" to "︖",
        "¡" to "︕",
        "." to "︒",
        "。" to "︒",
        ";" to "︔",
        "；" to "︔",
        ":" to "︓",
        "：" to "︓",
        "," to "︐",
        "，" to "︐",
        "‚" to "︐",
        "„" to "︐",
        "-" to "︲",
        "−" to "︲",
        "・" to "·",
    )

    /**
     * Maps vertical CJK punctuation to horizontal forms.
     * Inverse of [horizontalToVertical]. Since H2V may have duplicate values,
     * this uses the first-match heuristic — consistent with Python's broken
     * `{**dict(zip(CJK_H2V.items(), CJK_H2V.keys()))}` which also drops
     * duplicates silently.
     */
    val verticalToHorizontal: Map<String, String> by lazy {
        horizontalToVertical.entries.associate { (k, v) -> v to k }
    }

    /**
     * Finds the horizontal form for a vertical character by searching H2V entries.
     * This handles the case where multiple horizontal chars share the same
     * vertical form (e.g., "(" and "（" both map to "︵").
     */
    private fun findHorizontal(char: String): String {
        return horizontalToVertical.entries.find { it.value == char }?.key ?: char
    }

    /**
     * Translates a character for the target direction.
     *
     * @param char single character string to translate
     * @param direction 0 = horizontal, 1 = vertical
     * @return the translated character string (may be multi-char in rare cases)
     */
    fun translateForDirection(char: String, direction: Int): String {
        return when (direction) {
            0 -> findHorizontal(char)
            1 -> horizontalToVertical[char] ?: char
            else -> char
        }
    }

    /**
     * Translates all CJK punctuation in a string for vertical rendering.
     */
    fun translateForVertical(text: String): String = buildString(text.length) {
        for (ch in text) {
            append(translateForDirection(ch.toString(), 1))
        }
    }

    /**
     * Translates all CJK punctuation in a string for horizontal rendering.
     */
    fun translateForHorizontal(text: String): String = buildString(text.length) {
        for (ch in text) {
            append(translateForDirection(ch.toString(), 0))
        }
    }
}
