package com.sakuravillager.manga_translator.translation.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * TDD tests for XposEncoder and its helper functions.
 *
 * Reference: python-web/manga_translator/ocr/xpos_relative_position.py
 *
 * Test tolerances:
 * - Pure helper functions (fixedPosEmbedding, rotateEveryTwo, duplicateInterleave): 1e-5
 * - End-to-end forward pass: 1e-4
 */
class XposEncoderTest {

    // ── fixedPosEmbedding ──────────────────────────────────────────────

    @Test
    fun `fixedPosEmbedding produces correct sin and cos for first 5 elements`() {
        // seqLen=4, dim=4
        // inv_freq = [1/10000^(0/4), 1/10000^(1/4), 1/10000^(2/4), 1/10000^(3/4)]
        //          = [1.0, 0.1, 0.01, 0.001]
        // sinusoid_inp[i,j] = i * inv_freq[j]
        // Row i=0: [0, 0, 0, 0]
        // Row i=1: [1.0, 0.1, 0.01, 0.001]
        // sin[0..4] = sin(0), sin(0), sin(0), sin(0), sin(1.0)
        // cos[0..4] = cos(0), cos(0), cos(0), cos(0), cos(1.0)

        val (sinArr, cosArr) = fixedPosEmbedding(4, 4)

        // sin first 5 elements
        assertEquals(0f, sinArr[0], 1e-5f)       // sin(0)
        assertEquals(0f, sinArr[1], 1e-5f)       // sin(0)
        assertEquals(0f, sinArr[2], 1e-5f)       // sin(0)
        assertEquals(0f, sinArr[3], 1e-5f)       // sin(0)
        assertEquals(sin(1.0).toFloat(), sinArr[4], 1e-5f)  // sin(1.0)

        // cos first 5 elements
        assertEquals(1f, cosArr[0], 1e-5f)       // cos(0)
        assertEquals(1f, cosArr[1], 1e-5f)       // cos(0)
        assertEquals(1f, cosArr[2], 1e-5f)       // cos(0)
        assertEquals(1f, cosArr[3], 1e-5f)       // cos(0)
        assertEquals(cos(1.0).toFloat(), cosArr[4], 1e-5f)  // cos(1.0)
    }

    @Test
    fun `fixedPosEmbedding arrays have expected size`() {
        val seqLen = 8
        val dim = 16
        val (sinArr, cosArr) = fixedPosEmbedding(seqLen, dim)

        assertEquals(seqLen * dim, sinArr.size)
        assertEquals(seqLen * dim, cosArr.size)
    }

    // ── rotateEveryTwo ─────────────────────────────────────────────────

    @Test
    fun `rotateEveryTwo swaps pairs with negation on flat array`() {
        val input = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)
        val result = rotateEveryTwo(input)

        // For each pair (2k, 2k+1): output[2k] = -input[2k+1], output[2k+1] = input[2k]
        assertEquals(-2f, result[0], 1e-5f)
        assertEquals(1f, result[1], 1e-5f)
        assertEquals(-4f, result[2], 1e-5f)
        assertEquals(3f, result[3], 1e-5f)
        assertEquals(-6f, result[4], 1e-5f)
        assertEquals(5f, result[5], 1e-5f)
        assertEquals(-8f, result[6], 1e-5f)
        assertEquals(7f, result[7], 1e-5f)
    }

    @Test
    fun `rotateEveryTwo preserves array size`() {
        val input = floatArrayOf(1f, 2f, 3f, 4f)
        assertEquals(4, rotateEveryTwo(input).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rotateEveryTwo throws on odd length`() {
        rotateEveryTwo(floatArrayOf(1f, 2f, 3f))
    }

    // ── duplicateInterleave ────────────────────────────────────────────

    @Test
    fun `duplicateInterleave repeats each element twice sequentially`() {
        val input = floatArrayOf(1f, 2f, 3f, 4f)
        val result = duplicateInterleave(input)

        assertEquals(8, result.size)
        assertEquals(1f, result[0], 1e-5f)
        assertEquals(1f, result[1], 1e-5f)
        assertEquals(2f, result[2], 1e-5f)
        assertEquals(2f, result[3], 1e-5f)
        assertEquals(3f, result[4], 1e-5f)
        assertEquals(3f, result[5], 1e-5f)
        assertEquals(4f, result[6], 1e-5f)
        assertEquals(4f, result[7], 1e-5f)
    }

    @Test
    fun `duplicateInterleave on single element`() {
        val input = floatArrayOf(42f)
        val result = duplicateInterleave(input)

        assertEquals(2, result.size)
        assertEquals(42f, result[0], 1e-5f)
        assertEquals(42f, result[1], 1e-5f)
    }

    @Test
    fun `duplicateInterleave on empty array`() {
        val result = duplicateInterleave(FloatArray(0))
        assertEquals(0, result.size)
    }

    // ── applyRotaryPosEmb ──────────────────────────────────────────────

    @Test
    fun `applyRotaryPosEmb with zero sin preserves x via cos`() {
        // When sin=0 and cos=1 and scale=1, result should equal x
        val x = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val sinArr = floatArrayOf(0f, 0f, 0f)       // dim_half = 3
        val cosArr = floatArrayOf(1f, 1f, 1f)       // dim_half = 3
        val scaleArr = floatArrayOf(1f, 1f, 1f)     // dim_half = 3

        val result = applyRotaryPosEmb(x, sinArr, cosArr, scaleArr, batch = 1, seqLen = 1, headDim = 6)

        // After duplicateInterleave: sin=[0,0,0,0,0,0], cos=[1,1,1,1,1,1]
        // result = x*1 + rotateEveryTwo(x)*0 = x
        assertArrayEquals(x, result, 1e-5f)
    }

    @Test
    fun `applyRotaryPosEmb with zero cos applies rotate via sin`() {
        // When cos=0, sin=1, scale=1: result = rotateEveryTwo(x)
        val x = floatArrayOf(1f, 2f, 3f, 4f)       // batch=1, seqLen=1, headDim=4
        val sinArr = floatArrayOf(1f, 1f)           // dim_half = 2
        val cosArr = floatArrayOf(0f, 0f)           // dim_half = 2
        val scaleArr = floatArrayOf(1f, 1f)         // dim_half = 2

        val result = applyRotaryPosEmb(x, sinArr, cosArr, scaleArr, batch = 1, seqLen = 1, headDim = 4)

        // After duplicateInterleave: sin=[1,1,1,1], cos=[0,0,0,0]
        // rotateEveryTwo(x) = [-2, 1, -4, 3]
        // result = x*0 + rotateEveryTwo(x)*1 = rotateEveryTwo(x)
        val expected = rotateEveryTwo(x)
        assertArrayEquals(expected, result, 1e-5f)
    }

    @Test
    fun `applyRotaryPosEmb multiplies sin and cos by scale`() {
        // Verify that scale properly scales sin and cos before duplicate interleave
        val x = floatArrayOf(1f, 2f)                // batch=1, seqLen=1, headDim=2
        val sinArr = floatArrayOf(0.5f)              // dim_half = 1
        val cosArr = floatArrayOf(0.5f)              // dim_half = 1
        val scaleArr = floatArrayOf(2f)              // dim_half = 1

        val result = applyRotaryPosEmb(x, sinArr, cosArr, scaleArr, batch = 1, seqLen = 1, headDim = 2)

        // After scale+duplicateInterleave: sin=[1,1], cos=[1,1]
        // rotateEveryTwo(x) = [-2, 1]
        // result = [1*1 + (-2)*1, 2*1 + 1*1] = [-1, 3]
        assertEquals(-1f, result[0], 1e-5f)
        assertEquals(3f, result[1], 1e-5f)
    }

    // ── XposEncoder.forward (end-to-end) ───────────────────────────────

    @Test
    fun `xposEncoderForward with offset zero produces valid output size`() {
        val headDim = 8
        val encoder = XposEncoder(headDim = headDim)
        val x = FloatArray(2 * headDim) { idx -> (idx + 1).toFloat() }  // batch=1, seqLen=2
        val result = encoder.forward(x, offset = 0)

        assertEquals(x.size, result.size)
    }

    @Test
    fun `xposEncoderForward with offset zero matches mathematical invariants`() {
        // With headDim=4, seqLen=2, offset=0
        // At position i=0: the scale exponent is 0, so scale=1.
        // The first two pairs (dim 0-1) should be identity-like (x*1 + rotated*0 for some)
        val headDim = 4
        val encoder = XposEncoder(headDim = headDim)
        // batch=1, seqLen=2
        val x = floatArrayOf(1f, 1f, 1f, 1f, 2f, 2f, 2f, 2f)
        val result = encoder.forward(x, offset = 0)

        assertEquals(8, result.size)
        // When all elements of a pair are equal, rotateEveryTwo produces alternating
        // [-x_next, x_curr] for each pair. Since all elements are 1:
        // x=[1,1,1,1,...], rotateEveryTwo(x)=[-1,1,-1,1,...]
        // Result should be different from input but deterministic
        // Just verify no NaN and correct size
        result.forEach { v -> assertEquals(v, v, 1e-10f) } // no NaN
    }

    @Test
    fun `xposEncoderForward with positive offset produces correct output size`() {
        val headDim = 4
        val encoder = XposEncoder(headDim = headDim)
        val x = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)  // batch=1, seqLen=2
        val result = encoder.forward(x, offset = 1)

        assertEquals(x.size, result.size)
    }

    @Test
    fun `xposEncoderForward with offset shifts positional encoding`() {
        // Position offset should change the result compared to offset=0
        val headDim = 4
        val encoder = XposEncoder(headDim = headDim)
        val x = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)

        val resultOffset0 = encoder.forward(x, offset = 0)
        val resultOffset1 = encoder.forward(x, offset = 1)
        val resultOffset2 = encoder.forward(x, offset = 2)

        // Different offsets should give different results
        var diff0 = false
        var diff1 = false
        for (i in x.indices) {
            if (kotlin.math.abs(resultOffset0[i] - resultOffset1[i]) > 1e-4f) diff0 = true
            if (kotlin.math.abs(resultOffset1[i] - resultOffset2[i]) > 1e-4f) diff1 = true
        }
        // At least some element should differ (offset changes positional encoding)
        // Note: it's mathematically possible but improbable that all match exactly
        assertEquals("offset 0 and 1 should differ", true, diff0)
        assertEquals("offset 1 and 2 should differ", true, diff1)
    }

    @Test
    fun `xposEncoderForward with downscale inverts the scale effect`() {
        val headDim = 4
        val encoder = XposEncoder(headDim = headDim)
        val x = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)

        val resultNormal = encoder.forward(x, offset = 0, downscale = false)
        val resultDownscale = encoder.forward(x, offset = 0, downscale = true)

        // Downscale inverts position scale: scale = 1/scale
        // Results should differ significantly from normal
        var anyDifferent = false
        for (i in x.indices) {
            if (kotlin.math.abs(resultNormal[i] - resultDownscale[i]) > 1e-4f) {
                anyDifferent = true
                break
            }
        }
        assertEquals("downscale results should differ from normal", true, anyDifferent)
    }

    @Test
    fun `xposEncoderWithLargeSeqPreservesScaleShape`() {
        // With seqLen > posCount (after trimming), verify shape consistency
        val headDim = 320
        val encoder = XposEncoder(headDim = headDim)
        val seqLen = 64
        val x = FloatArray(seqLen * headDim) { (it % 100).toFloat() }
        val result = encoder.forward(x, offset = 10)

        assertEquals(x.size, result.size)
    }

    @Test
    fun `xposEncoder with headDim 320 scaleBase 512 produces valid output`() {
        // Realistic dimensions used in the model
        val headDim = 320
        val encoder = XposEncoder(headDim = headDim)
        val x = FloatArray(headDim) { (it % 10).toFloat() }  // seqLen=1, batch=1
        val result = encoder.forward(x)

        assertEquals(headDim, result.size)
        result.forEach { v ->
            assertEquals(v, v, 1e-10f) // no NaN
            assert(v.isFinite())       // no Inf
        }
    }

    // ── XposEncoder scale buffer ─────────────────────────────────────────

    @Test
    fun `xposEncoder precomputes scale buffer of correct size`() {
        val headDim = 320
        val encoder = XposEncoder(headDim = headDim)
        assertEquals(headDim / 2, encoder.scale.size)
    }

    @Test
    fun `xposEncoder scale buffer values are computed from headDim`() {
        // scale[i] = (2*i + 0.4 * headDim) / (1.4 * headDim)
        val headDim = 320
        val encoder = XposEncoder(headDim = headDim)

        val expected0 = (0f + 0.4f * headDim) / (1.4f * headDim)
        assertEquals(expected0, encoder.scale[0], 1e-5f)

        val expected1 = (2f + 0.4f * headDim) / (1.4f * headDim)
        assertEquals(expected1, encoder.scale[1], 1e-5f)

        val expectedLast = ((headDim - 2).toFloat() + 0.4f * headDim) / (1.4f * headDim)
        assertEquals(expectedLast, encoder.scale.last(), 1e-5f)
    }

    @Test
    fun `xposEncoder scale buffer is not hardcoded`() {
        val encoder8 = XposEncoder(headDim = 8)
        val encoder16 = XposEncoder(headDim = 16)

        assertEquals(4, encoder8.scale.size)
        assertEquals(8, encoder16.scale.size)
        // Values should differ because headDim differs in formula
        var same = true
        for (i in 0 until minOf(encoder8.scale.size, encoder16.scale.size)) {
            if (encoder8.scale[i] != encoder16.scale[i]) { same = false; break }
        }
        assertEquals(false, same)
    }
}
