package com.sakuravillager.manga_translator.translation.ocr

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads the AR OCR character dictionary from [models/alphabet-all-v7.txt]
 * and decodes model token IDs back to text.
 *
 * The AR model does NOT use CTC blank token.
 * Token 0 = <PAD> (UNUSED).
 * Decode: direct lookup without CTC collapse.
 */
object ArDictionary {

    const val START = 1
    const val END = 2
    const val PAD = 0

    @Volatile
    private var _chars: List<String>? = null

    val chars: List<String>
        get() = _chars ?: error("ArDictionary not loaded. Call load(context) first.")

    val size: Int
        get() = chars.size

    /**
     * Loads dictionary from assets/models/alphabet-all-v7.txt.
     * Cached after first call.
     */
    fun load(context: Context): List<String> {
        _chars?.let { return it }
        val reader = BufferedReader(
            InputStreamReader(context.assets.open("models/alphabet-all-v7.txt"), "UTF-8"),
        )
        val lines = reader.readLines()
        reader.close()
        _chars = lines
        return lines
    }

    /**
     * Decodes AR model token IDs back to text string.
     *
     * Direct token-to-character lookup (no CTC collapse).
     * <SP> is mapped to ' ' (space).
     *
     * @param tokens list of token IDs produced by the AR model
     * @return decoded text string
     */
    fun decode(tokens: List<Int>): String {
        val charsList = _chars ?: error("ArDictionary not loaded.")
        val sb = StringBuilder(tokens.size)
        for (id in tokens) {
            if (id !in charsList.indices) continue
            when (charsList[id]) {
                "<SP>" -> sb.append(' ')
                else -> sb.append(charsList[id])
            }
        }
        return sb.toString()
    }
}
