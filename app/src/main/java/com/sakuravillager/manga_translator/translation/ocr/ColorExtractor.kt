package com.sakuravillager.manga_translator.translation.ocr

import kotlin.math.max

/**
 * Result of color prediction for a single character.
 *
 * Mirrors the Python model_48px.py color output: fg/bg RGB values
 * in [0, 255] plus indicator booleans that gate whether the predicted
 * color is actually present.
 *
 * @property fgRgb  foreground RGB triplet, each channel in [0, 255]
 * @property bgRgb  background RGB triplet, each channel in [0, 255]
 * @property hasFg  true when fg_ind[1] > fg_ind[0] (indicator gating)
 * @property hasBg  true when bg_ind[1] > bg_ind[0] (indicator gating)
 */
data class CharacterColor(
    val fgRgb: IntArray,
    val bgRgb: IntArray,
    val hasFg: Boolean,
    val hasBg: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CharacterColor) return false
        return fgRgb.contentEquals(other.fgRgb) &&
                bgRgb.contentEquals(other.bgRgb) &&
                hasFg == other.hasFg &&
                hasBg == other.hasBg
    }

    override fun hashCode(): Int {
        var result = fgRgb.contentHashCode()
        result = 31 * result + bgRgb.contentHashCode()
        result = 31 * result + hasFg.hashCode()
        result = 31 * result + hasBg.hashCode()
        return result
    }

    override fun toString(): String {
        return "CharacterColor(fgRgb=${fgRgb.contentToString()}, bgRgb=${bgRgb.contentToString()}, " +
                "hasFg=$hasFg, hasBg=$hasBg)"
    }
}

/**
 * Serialisable weights for the color prediction MLP.
 *
 * Mirrors the six Linear layers from Python model_48px.py:
 *   color_pred1:  Linear(320 → 64)
 *   color_pred_fg:  Linear(64 → 3)
 *   color_pred_bg:  Linear(64 → 3)
 *   color_pred_fg_ind:  Linear(64 → 2)
 *   color_pred_bg_ind:  Linear(64 → 2)
 *
 * All weights are stored row-major as flat [inFeatures × outFeatures]
 * arrays so that weight[i * outFeatures + j] is the connection from
 * input[i] to output[j].
 *
 * @property w1     [320 × 64] weight matrix for color_pred1
 * @property b1     [64] bias for color_pred1
 * @property wFg    [64 × 3] weight matrix for color_pred_fg
 * @property bFg    [3] bias for color_pred_fg
 * @property wBg    [64 × 3] weight matrix for color_pred_bg
 * @property bBg    [3] bias for color_pred_bg
 * @property wFgInd [64 × 2] weight matrix for color_pred_fg_ind
 * @property bFgInd [2] bias for color_pred_fg_ind
 * @property wBgInd [64 × 2] weight matrix for color_pred_bg_ind
 * @property bBgInd [2] bias for color_pred_bg_ind
 */
data class ColorWeights(
    val w1: FloatArray,
    val b1: FloatArray,
    val wFg: FloatArray,
    val bFg: FloatArray,
    val wBg: FloatArray,
    val bBg: FloatArray,
    val wFgInd: FloatArray,
    val bFgInd: FloatArray,
    val wBgInd: FloatArray,
    val bBgInd: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ColorWeights) return false
        return w1.contentEquals(other.w1) &&
                b1.contentEquals(other.b1) &&
                wFg.contentEquals(other.wFg) &&
                bFg.contentEquals(other.bFg) &&
                wBg.contentEquals(other.wBg) &&
                bBg.contentEquals(other.bBg) &&
                wFgInd.contentEquals(other.wFgInd) &&
                bFgInd.contentEquals(other.bFgInd) &&
                wBgInd.contentEquals(other.wBgInd) &&
                bBgInd.contentEquals(other.bBgInd)
    }

    override fun hashCode(): Int {
        var result = w1.contentHashCode()
        result = 31 * result + b1.contentHashCode()
        result = 31 * result + wFg.contentHashCode()
        result = 31 * result + bFg.contentHashCode()
        result = 31 * result + wBg.contentHashCode()
        result = 31 * result + bBg.contentHashCode()
        result = 31 * result + wFgInd.contentHashCode()
        result = 31 * result + bFgInd.contentHashCode()
        result = 31 * result + wBgInd.contentHashCode()
        result = 31 * result + bBgInd.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "ColorWeights(w1=[${w1.size}], b1=[${b1.size}], " +
                "wFg=[${wFg.size}], bFg=[${bFg.size}], " +
                "wBg=[${wBg.size}], bBg=[${bBg.size}], " +
                "wFgInd=[${wFgInd.size}], bFgInd=[${bFgInd.size}], " +
                "wBgInd=[${wBgInd.size}], bBgInd=[${bBgInd.size}])"
    }
}

/**
 * Pure-Kotlin color predictor that mirrors the color_pred layers from
 * Python model_48px.py.
 *
 * Architecture (applied sequentially on the decoder's final hidden state):
 *   1. color_pred1: Linear(320 → 64) + ReLU
 *   2. color_pred_fg:  Linear(64 → 3)  → foreground RGB
 *   3. color_pred_bg:  Linear(64 → 3)  → background RGB
 *   4. color_pred_fg_ind: Linear(64 → 2) → foreground indicator
 *   5. color_pred_bg_ind: Linear(64 → 2) → background indicator
 *
 * Indicator gating (matches model_48px.py:846-851):
 *   hasFg = fg_ind[1] > fg_ind[0]
 *   hasBg = bg_ind[1] > bg_ind[0]
 *
 * Color values are multiplied by 255 and clamped to [0, 255].
 *
 * @param colorWeights the trained weights for all five Linear layers
 */
class ColorExtractor(private val colorWeights: ColorWeights) {

    /**
     * Predicts character colors from the decoder's final hidden state.
     *
     * @param decoderActivations FloatArray of size 320 — the last-step
     *                           decoder hidden state (cf. Hypothesis.cachedActivations[-1]).
     * @return [CharacterColor] with predicted fg/bg RGB + indicator gates
     */
    fun extract(decoderActivations: FloatArray): CharacterColor {
        require(decoderActivations.size == 320) {
            "decoderActivations must have size 320, got ${decoderActivations.size}"
        }

        // 1. color_pred1: Linear(320 → 64) + ReLU
        val hidden = FloatArray(64)
        linear(decoderActivations, colorWeights.w1, colorWeights.b1, hidden, 320, 64)
        for (i in 0 until 64) {
            hidden[i] = max(0f, hidden[i])
        }

        // 2. Four parallel heads on the shared 64-dim hidden representation
        val fgPred = FloatArray(3)
        linear(hidden, colorWeights.wFg, colorWeights.bFg, fgPred, 64, 3)

        val bgPred = FloatArray(3)
        linear(hidden, colorWeights.wBg, colorWeights.bBg, bgPred, 64, 3)

        val fgInd = FloatArray(2)
        linear(hidden, colorWeights.wFgInd, colorWeights.bFgInd, fgInd, 64, 2)

        val bgInd = FloatArray(2)
        linear(hidden, colorWeights.wBgInd, colorWeights.bBgInd, bgInd, 64, 2)

        // 3. Scale [0, 1] range to [0, 255] with clamping
        val fgRgb = IntArray(3) { (fgPred[it] * 255f).toInt().coerceIn(0, 255) }
        val bgRgb = IntArray(3) { (bgPred[it] * 255f).toInt().coerceIn(0, 255) }

        // 4. Indicator gating
        val hasFg = fgInd[1] > fgInd[0]
        val hasBg = bgInd[1] > bgInd[0]

        return CharacterColor(fgRgb, bgRgb, hasFg, hasBg)
    }

    companion object {
        /**
         * Performs y = x @ W + b for a single linear layer.
         *
         * @param input       input vector, length [inFeatures]
         * @param weight      flat [inFeatures × outFeatures] array in row-major order
         * @param bias        bias vector, length [outFeatures]
         * @param output      pre-allocated output vector, length [outFeatures]
         * @param inFeatures  number of input features
         * @param outFeatures number of output features
         */
        private fun linear(
            input: FloatArray,
            weight: FloatArray,
            bias: FloatArray,
            output: FloatArray,
            inFeatures: Int,
            outFeatures: Int
        ) {
            for (j in 0 until outFeatures) {
                var sum = bias[j]
                for (i in 0 until inFeatures) {
                    sum += input[i] * weight[i * outFeatures + j]
                }
                output[j] = sum
            }
        }

        /**
         * Creates a [ColorExtractor] with default weights.
         *
         * Attempts to load weights from a Python-exported file. If the
         * file does not exist or loading fails, returns a mock extractor
         * with zero-ish weights (sufficient for structure verification).
         */
        fun createDefault(): ColorExtractor {
            return try {
                // TODO: Load from Python exported weights file
                // e.g. loadWeightsFromFile("models/color_weights.bin")
                createMock()
            } catch (_: Exception) {
                createMock()
            }
        }

        /**
         * Creates a [ColorExtractor] with mock weights that produce
         * deterministic (but meaningless) outputs — useful for testing
         * structure and integration before trained weights are available.
         */
        fun createMock(): ColorExtractor {
            return ColorExtractor(
                ColorWeights(
                    w1 = FloatArray(320 * 64),
                    b1 = FloatArray(64),
                    wFg = FloatArray(64 * 3),
                    bFg = FloatArray(3),
                    wBg = FloatArray(64 * 3),
                    bBg = FloatArray(3),
                    wFgInd = FloatArray(64 * 2),
                    bFgInd = FloatArray(2),
                    wBgInd = FloatArray(64 * 2),
                    bBgInd = FloatArray(2)
                )
            )
        }
    }
}
