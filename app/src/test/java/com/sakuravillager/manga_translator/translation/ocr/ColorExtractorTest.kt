package com.sakuravillager.manga_translator.translation.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD tests for [ColorExtractor].
 *
 * Verifies the color prediction and indicator gating logic
 * that mirrors Python model_48px.py's color_pred layers.
 */
class ColorExtractorTest {

    // ── Test helpers ───────────────────────────────────────────────────

    /**
     * Builds a w1 [320→64] weight array where only the first [n] input
     * connections to output index 0 are set to [value].
     */
    private fun w1FirstN(n: Int, value: Float = 1f): FloatArray {
        val w = FloatArray(320 * 64)
        for (i in 0 until n.coerceAtMost(320)) {
            w[i * 64 + 0] = value
        }
        return w
    }

    /**
     * Builds a weight array of size [inFeatures] × [outFeatures] where
     * only weight[inputIdx * outFeatures + outputIdx] = [value].
     */
    private fun singleWeight(inFeatures: Int, outFeatures: Int, inputIdx: Int, outputIdx: Int, value: Float = 1f): FloatArray {
        val w = FloatArray(inFeatures * outFeatures)
        w[inputIdx * outFeatures + outputIdx] = value
        return w
    }

    /**
     * Creates a [ColorExtractor] whose first Linear layer maps
     * [decoderInput] element 0 to hidden[0], and whose subsequent
     * heads use single-weight connections from hidden[0].
     *
     * @param fgWeight   weight from hidden[0] to each fg_pred output
     * @param bgWeight   weight from hidden[0] to each bg_pred output
     * @param fgInd0     weight from hidden[0] to fg_ind[0]
     * @param fgInd1     weight from hidden[0] to fg_ind[1]
     * @param bgInd0     weight from hidden[0] to bg_ind[0]
     * @param bgInd1     weight from hidden[0] to bg_ind[1]
     * @param fgBias     bias for fg_pred
     * @param bgBias     bias for bg_pred
     * @param fgIndBias  bias for fg_ind
     * @param bgIndBias  bias for bg_ind
     */
    private fun createSimpleExtractor(
        fgWeight: FloatArray = floatArrayOf(0.5f, 0f, 0f),
        bgWeight: FloatArray = floatArrayOf(0f, 0.5f, 0f),
        fgInd0: Float = 0f,
        fgInd1: Float = 1f,
        bgInd0: Float = 0f,
        bgInd1: Float = 1f,
        fgBias: FloatArray = floatArrayOf(0f, 0f, 0f),
        bgBias: FloatArray = floatArrayOf(0f, 0f, 0f),
        fgIndBias: FloatArray = floatArrayOf(0f, 0f),
        bgIndBias: FloatArray = floatArrayOf(0f, 0f)
    ): ColorExtractor {
        val w1 = w1FirstN(1, 1f)  // only input[0] → hidden[0]
        val wFg = singleWeight(64, 3, 0, 0, fgWeight[0]).also {
            if (fgWeight.size > 1) it[0 * 3 + 1] = fgWeight[1]
            if (fgWeight.size > 2) it[0 * 3 + 2] = fgWeight[2]
        }
        val wBg = singleWeight(64, 3, 0, 0, bgWeight[0]).also {
            if (bgWeight.size > 1) it[0 * 3 + 1] = bgWeight[1]
            if (bgWeight.size > 2) it[0 * 3 + 2] = bgWeight[2]
        }
        val wFgInd = singleWeight(64, 2, 0, 0, fgInd0).also { it[0 * 2 + 1] = fgInd1 }
        val wBgInd = singleWeight(64, 2, 0, 0, bgInd0).also { it[0 * 2 + 1] = bgInd1 }

        val weights = ColorWeights(
            w1 = w1,
            b1 = FloatArray(64),
            wFg = wFg,
            bFg = fgBias,
            wBg = wBg,
            bBg = bgBias,
            wFgInd = wFgInd,
            bFgInd = fgIndBias,
            wBgInd = wBgInd,
            bBgInd = bgIndBias
        )
        return ColorExtractor(weights)
    }

    // ── Tests ──────────────────────────────────────────────────────────

    @Test
    fun `testExtractReturnsCorrectShape`() {
        val extractor = ColorExtractor.createDefault()
        val input = FloatArray(320) { 0.5f }
        val result = extractor.extract(input)

        assertEquals("fgRgb must have 3 elements", 3, result.fgRgb.size)
        assertEquals("bgRgb must have 3 elements", 3, result.bgRgb.size)
        // hasFg and hasBg are booleans, structure-verified if above pass
    }

    @Test
    fun `testColorPrediction given known weights and input verify matching expected output`() {
        // Setup: input[0] = 2.0f, hidden[0] = 2.0 (w1[0]=1, b1[0]=0, ReLU keeps 2.0)
        // fg_pred = [2.0 * 0.25, 2.0 * 0.5, 2.0 * 0.75] = [0.5, 1.0, 1.5]
        // bg_pred = [2.0 * 0.1, 2.0 * 0.2, 2.0 * 0.3] = [0.2, 0.4, 0.6]
        // fgRgb = [clamp(0.5*255), clamp(1.0*255), clamp(1.5*255)]
        //       = [127, 255, 255]  (1.5*255=382.5->382, clamped to 255)
        // bgRgb = [clamp(0.2*255), clamp(0.4*255), clamp(0.6*255)]
        //       = [51, 102, 153]
        val input = FloatArray(320)
        input[0] = 2.0f

        val extractor = createSimpleExtractor(
            fgWeight = floatArrayOf(0.25f, 0.5f, 0.75f),
            bgWeight = floatArrayOf(0.1f, 0.2f, 0.3f),
            fgInd0 = 0f, fgInd1 = 1f,  // hasFg = true
            bgInd0 = 0f, bgInd1 = 1f   // hasBg = true
        )
        val result = extractor.extract(input)

        assertArrayEquals("fgRgb", intArrayOf(127, 255, 255), result.fgRgb)
        assertArrayEquals("bgRgb", intArrayOf(51, 102, 153), result.bgRgb)
    }

    @Test
    fun `testIndicatorGatingFg when fg_ind_1 greater than fg_ind_0 hasFg is true`() {
        val extractor = createSimpleExtractor(fgInd0 = 0f, fgInd1 = 1f)
        val input = FloatArray(320) { 1.0f }
        val result = extractor.extract(input)
        assertTrue("hasFg should be true when fg_ind[1] > fg_ind[0]", result.hasFg)
    }

    @Test
    fun `testIndicatorGatingBg when bg_ind_1 greater than bg_ind_0 hasBg is true`() {
        val extractor = createSimpleExtractor(bgInd0 = 0f, bgInd1 = 1f)
        val input = FloatArray(320) { 1.0f }
        val result = extractor.extract(input)
        assertTrue("hasBg should be true when bg_ind[1] > bg_ind[0]", result.hasBg)
    }

    @Test
    fun `testIndicatorFgFalse when fg_ind_1 less than or equal to fg_ind_0 hasFg is false`() {
        // fg_ind[0] = 2.0, fg_ind[1] = 1.0 → hasFg = false
        val extractor = createSimpleExtractor(fgInd0 = 2f, fgInd1 = 1f)
        val input = FloatArray(320) { 1.0f }
        val result = extractor.extract(input)
        assertFalse("hasFg should be false when fg_ind[1] <= fg_ind[0]", result.hasFg)
    }

    @Test
    fun `testIndicatorFgFalse when equal hasFg is false`() {
        // fg_ind[0] = fg_ind[1] = 1.0 → hasFg = false (not strictly greater)
        val extractor = createSimpleExtractor(fgInd0 = 1f, fgInd1 = 1f)
        val input = FloatArray(320) { 1.0f }
        val result = extractor.extract(input)
        assertFalse("hasFg should be false when fg_ind[1] == fg_ind[0]", result.hasFg)
    }

    @Test
    fun `testColorClipping color values clamped to 0 to 255`() {
        // fg_pred[0] = 2.0 → 2.0*255 = 510 → clamped to 255
        // fg_pred[1] = -0.5 → -0.5*255 = -127.5 → clamped to 0
        // fg_pred[2] = 0.3 → 0.3*255 = 76.5 → 76
        val input = FloatArray(320)
        input[0] = 1.0f

        val extractor = createSimpleExtractor(
            fgWeight = floatArrayOf(2.0f, -0.5f, 0.3f),
            fgBias = floatArrayOf(0f, 0f, 0f)
        )
        val result = extractor.extract(input)

        assertEquals("fgRgb[0] clamped to 255", 255, result.fgRgb[0])
        assertEquals("fgRgb[1] clamped to 0", 0, result.fgRgb[1])
        assertEquals("fgRgb[2] unclamped", 76, result.fgRgb[2])
    }

    @Test
    fun `testBgColorClipping bg values clamped to 0 to 255`() {
        val input = FloatArray(320)
        input[0] = 1.0f

        val extractor = createSimpleExtractor(
            bgWeight = floatArrayOf(-1.0f, 3.0f, 0.4f),
            bgBias = floatArrayOf(0f, 0f, 0f)
        )
        val result = extractor.extract(input)

        assertEquals("bgRgb[0] clamped to 0", 0, result.bgRgb[0])
        assertEquals("bgRgb[1] clamped to 255", 255, result.bgRgb[1])
        assertEquals("bgRgb[2] unclamped", 102, result.bgRgb[2])
    }

    @Test
    fun `testColorValuesMultipliedBy255 verify 0 to 1 to 0 to 255 scaling`() {
        // fg_pred = [0.2, 0.5, 0.8] → fgRgb = [51, 127, 204]
        val input = FloatArray(320)
        input[0] = 1.0f

        val extractor = createSimpleExtractor(
            fgWeight = floatArrayOf(0.2f, 0.5f, 0.8f),
            fgInd0 = 0f, fgInd1 = 1f,
            bgInd0 = 0f, bgInd1 = 1f
        )
        val result = extractor.extract(input)

        assertEquals("fgRgb[0] = 0.2 * 255 = 51", 51, result.fgRgb[0])
        assertEquals("fgRgb[1] = 0.5 * 255 = 127", 127, result.fgRgb[1])
        assertEquals("fgRgb[2] = 0.8 * 255 = 204", 204, result.fgRgb[2])
    }

    @Test
    fun `testBgColorValuesMultipliedBy255 verify 0 to 1 to 0 to 255 scaling`() {
        val input = FloatArray(320)
        input[0] = 1.0f

        val extractor = createSimpleExtractor(
            bgWeight = floatArrayOf(0.1f, 0.6f, 0.9f),
            fgInd0 = 0f, fgInd1 = 1f,
            bgInd0 = 0f, bgInd1 = 1f
        )
        val result = extractor.extract(input)

        assertEquals("bgRgb[0] = 0.1 * 255 = 25", 25, result.bgRgb[0])
        assertEquals("bgRgb[1] = 0.6 * 255 = 153", 153, result.bgRgb[1])
        assertEquals("bgRgb[2] = 0.9 * 255 = 229", 229, result.bgRgb[2])
    }

    @Test
    fun `testReLUAppliesToColorPred1 hidden layer`() {
        // w1 maps input[0] to hidden[0] with weight 1.0, and input[1] to hidden[1]
        // with weight -1.0. So:
        //   hidden[0] = input[0]*1.0 + 0 = input[0], after ReLU: max(0, input[0])
        //   hidden[1] = input[1]*(-1.0) + 0 = -input[1], after ReLU: max(0, -input[1])
        //
        // With input = [1.0, 1.0, 0, ...]:
        //   hidden[0] = 1.0 → ReLU → 1.0
        //   hidden[1] = -1.0 → ReLU → 0.0
        //
        // fg_pred[0] = hidden[0]*1.0 + hidden[1]*0 = 1.0
        // fgRgb[0] = clamp(1.0*255) = 255
        val w1 = FloatArray(320 * 64)
        w1[0 * 64 + 0] = 1.0f   // input[0] → hidden[0]
        w1[1 * 64 + 1] = -1.0f  // input[1] → hidden[1] (negative, killed by ReLU)

        val wFg = FloatArray(64 * 3)
        wFg[0 * 3 + 0] = 1.0f  // hidden[0] → fg_pred[0]
        wFg[1 * 3 + 0] = 1.0f  // hidden[1] → fg_pred[0] (should be 0 after ReLU)

        val weights = ColorWeights(
            w1 = w1,
            b1 = FloatArray(64),
            wFg = wFg,
            bFg = floatArrayOf(0f, 0f, 0f),
            wBg = FloatArray(64 * 3),
            bBg = floatArrayOf(0f, 0f, 0f),
            wFgInd = FloatArray(64 * 2) { if (it == 1) 1f else 0f },
            bFgInd = floatArrayOf(0f, 0f),
            wBgInd = FloatArray(64 * 2) { if (it == 1) 1f else 0f },
            bBgInd = floatArrayOf(0f, 0f)
        )
        val extractor = ColorExtractor(weights)
        val input = FloatArray(320) { if (it < 2) 1.0f else 0f }
        val result = extractor.extract(input)

        // hidden[1] = -1.0 → ReLU → 0, so only hidden[0] contributes
        // fg_pred[0] = hidden[0]*1.0 + hidden[1]*1.0 = 1.0 + 0 = 1.0
        assertEquals("ReLU kills negative hidden, only positive contributes",
            255, result.fgRgb[0])
    }

    @Test
    fun `testExtractRequires320Elements`() {
        val extractor = ColorExtractor.createDefault()
        val badInput = FloatArray(64) { 0.5f }
        try {
            extractor.extract(badInput)
            // Should not reach here
            assertTrue("Should have thrown for wrong input size", false)
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }
}
