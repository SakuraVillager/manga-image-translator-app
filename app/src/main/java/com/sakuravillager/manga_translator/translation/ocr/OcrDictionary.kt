package com.sakuravillager.manga_translator.translation.ocr

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads the OCR character dictionary from [alphabet-all-v7.txt] in app assets
 * and decodes model token IDs back to text.
 *
 * Token layout (matched to Python model_48px.py vocabulary):
 * - 0: <PAD>   – padding, skip in decode
 * - 1: <S>     – start-of-sequence, skip in decode
 * - 2: </S>    – end-of-sequence, stop decode
 * - 3: <SEP>   – separator, skip in decode
 * - 4: <UNK>   – unknown, skip in decode
 * - 5: <SP>    – space, emit ' '
 * - 6+:        – actual UTF-8 characters from the alphabet file
 */
object OcrDictionary {

    // ── Special token IDs ──────────────────────────────────────────────
    const val PAD = 0
    const val START = 1
    const val END = 2
    const val SEP = 3
    const val UNK = 4
    const val SPACE = 5

    // ── Cached dictionary ──────────────────────────────────────────────
    @Volatile
    private var _chars: List<String>? = null

    /** The full character list indexed by token ID. */
    val chars: List<String>
        get() = _chars ?: error("OcrDictionary not loaded. Call load(context) first.")

    /** Number of entries in the dictionary (including special tokens). */
    val size: Int
        get() = chars.size

    // ── Load ───────────────────────────────────────────────────────────
    /**
     * Loads the dictionary from [alphabet-all-v7.txt] in the app assets folder.
     *
     * This is a fast synchronous local-file read. Calling it multiple times
     * is safe – the result is cached after the first load.
     */
    fun load(context: Context): List<String> {
        _chars?.let { return it }

        val reader = BufferedReader(
            InputStreamReader(context.assets.open("alphabet-all-v7.txt"), "UTF-8"),
        )
        val lines = reader.readLines()
        reader.close()

        _chars = lines
        return lines
    }

    // ── Decode ─────────────────────────────────────────────────────────
    /**
     * Decodes an array of token IDs produced by the OCR model back into
     * a human-readable string.
     *
     * - Special tokens (PAD / START / SEP / UNK) are silently skipped.
     * - END (</S>) stops decoding immediately.
     * - SPACE (<SP>) is emitted as a regular space character.
     * - All other IDs are looked up in the dictionary and appended.
     */
    fun decodeTokenIds(ids: IntArray): String {
        val charsList = _chars ?: error("OcrDictionary not loaded. Call load(context) first.")
        val sb = StringBuilder(ids.size)

        for (id in ids) {
            when (id) {
                PAD, START, SEP, UNK -> continue
                END -> break
                SPACE -> sb.append(' ')
                in charsList.indices -> sb.append(charsList[id])
            }
        }
        return sb.toString()
    }
}
