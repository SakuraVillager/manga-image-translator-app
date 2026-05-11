package com.sakuravillager.manga_translator.translation.ocr

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads the CTC OCR character dictionary from [models/alphabet-all-v5.txt]
 * and decodes model token IDs back to text.
 *
 * CTC-blind (blank) token is <PAD> = 0.
 * Decode: collapse consecutive identical non-blank tokens.
 */
object OcrDictionary {

    const val BLANK = 0
    const val START = 1
    const val END = 2
    const val SEP = 3
    const val UNK = 4
    const val SPACE = 5

    @Volatile
    private var _chars: List<String>? = null

    val chars: List<String>
        get() = _chars ?: error("OcrDictionary not loaded. Call load(context) first.")

    val size: Int
        get() = chars.size

    /**
     * Loads dictionary from assets/models/alphabet-all-v5.txt.
     * Cached after first call.
     */
    fun load(context: Context): List<String> {
        _chars?.let { return it }
        val reader = BufferedReader(
            InputStreamReader(context.assets.open("models/alphabet-all-v5.txt"), "UTF-8"),
        )
        val lines = reader.readLines()
        reader.close()
        _chars = lines
        return lines
    }

    /**
     * CTC greedy decode: argmax → collapse consecutive duplicates → remove blank.
     *
     * @param logits raw logits [T, D] or flattened [T * D]
     * @param T number of timesteps
     * @param D vocab size
     * @return list of (charToken, logProbability)
     */
    fun ctcGreedyDecode(logits: FloatArray, T: Int, D: Int): List<Pair<Int, Float>> {
        val result = mutableListOf<Pair<Int, Float>>()
        var lastId = BLANK
        for (t in 0 until T) {
            val offset = t * D
            // softmax → argmax
            var maxVal = Float.NEGATIVE_INFINITY
            var maxIdx = BLANK
            for (d in 0 until D) {
                if (logits[offset + d] > maxVal) {
                    maxVal = logits[offset + d]
                    maxIdx = d
                }
            }
            // CTC collapse
            if (maxIdx != BLANK && maxIdx != lastId) {
                result.add(maxIdx to maxVal)
            }
            lastId = maxIdx
        }
        return result
    }

    /**
     * Decodes CTC output to final text string.
     * Each token ID is looked up in the dictionary.
     * Match the Python reference: only blank is removed during CTC collapse;
     * dictionary entries are otherwise preserved, except <SP> → ' '.
     */
    fun ctcDecodeToText(decoded: List<Pair<Int, Float>>): String {
        val charsList = _chars ?: error("OcrDictionary not loaded.")
        val sb = StringBuilder(decoded.size)
        for ((id, _) in decoded) {
            if (id !in charsList.indices) continue
            when (charsList[id]) {
                "<SP>" -> sb.append(' ')
                else -> sb.append(charsList[id])
            }
        }
        return sb.toString()
    }

    /**
     * Extract average text color from CTC colors output.
     * Colors shape: [T, 6] = [fr, fg, fb, br, bg, bb]
     * Returns averaged (fr,fg,fb, br,bg,bb) for the kept characters.
     */
    fun extractColors(colors: FloatArray, T: Int, decodedSteps: List<Int>): Pair<IntArray, IntArray> {
        val n = decodedSteps.size
        if (n == 0) return IntArray(3) { 0 } to IntArray(3) { 255 }

        var sumFr = 0f; var sumFg = 0f; var sumFb = 0f
        var sumBr = 0f; var sumBg = 0f; var sumBb = 0f

        for (t in decodedSteps) {
            val o = t * 6
            sumFr += colors[o];   sumFg += colors[o + 1]; sumFb += colors[o + 2]
            sumBr += colors[o + 3]; sumBg += colors[o + 4]; sumBb += colors[o + 5]
        }

        fun clamp(v: Float) = (v * 255).toInt().coerceIn(0, 255)
        return intArrayOf(clamp(sumFr / n), clamp(sumFg / n), clamp(sumFb / n)) to
               intArrayOf(clamp(sumBr / n), clamp(sumBg / n), clamp(sumBb / n))
    }
}
