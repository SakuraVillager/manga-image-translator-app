package com.sakuravillager.manga_translator.translation.ocr

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * XPOS rotary position encoding, ported from
 * python-web/manga_translator/ocr/xpos_relative_position.py.
 *
 * All functions operate on flat [FloatArray]s. Where array semantics
 * imply multi-dimensional shapes (e.g. [batch, seqLen, headDim]), the
 * caller supplies explicit shape parameters.
 */

// ── Helper functions ──────────────────────────────────────────────────

/**
 * Compute fixed sinusoidal position embeddings.
 *
 * @param seqLen number of positions
 * @param dim    embedding dimension (must match the dimension of inv_freq)
 * @return Pair of (sin, cos) arrays, each of size [seqLen] * [dim], flat row-major.
 */
fun fixedPosEmbedding(seqLen: Int, dim: Int): Pair<FloatArray, FloatArray> {
    // inv_freq[j] = 1.0 / (10000 ** (j / dim))
    val invFreq = FloatArray(dim) { j ->
        (1.0 / 10000.0.pow(j.toDouble() / dim)).toFloat()
    }

    // sinusoid_inp[i, j] = i * inv_freq[j]
    val sinusoidInp = FloatArray(seqLen * dim) { idx ->
        val i = idx / dim  // position
        val j = idx % dim  // dimension
        i.toFloat() * invFreq[j]
    }

    return Pair(
        FloatArray(seqLen * dim) { sin(sinusoidInp[it].toDouble()).toFloat() },
        FloatArray(seqLen * dim) { cos(sinusoidInp[it].toDouble()).toFloat() }
    )
}

/**
 * Rotate every two elements along the last dimension.
 *
 * For each pair (x_{2k}, x_{2k+1}) in the flat array:
 *     output[2k]   = -input[2k+1]
 *     output[2k+1] =  input[2k]
 *
 * Equivalent to [torch.stack((-x2, x1), dim=-1).flatten(-2)].
 */
fun rotateEveryTwo(x: FloatArray): FloatArray {
    require(x.size % 2 == 0) { "Array size must be even, got ${x.size}" }
    val result = FloatArray(x.size)
    var i = 0
    while (i < x.size) {
        result[i] = -x[i + 1]   // stack -x2
        result[i + 1] = x[i]    // stack x1
        i += 2
    }
    return result
}

/**
 * Duplicate each element and interleave.
 *
 * For a flat input [a, b, c, d] representing a 2-D matrix with shape
 * [dim0, dim1], the output has shape [dim0, 2 * dim1] with elements
 * interleaved: [a, a, b, b, c, c, d, d].
 *
 * Equivalent to [m.view(-1, 1).repeat(1, 2).view(dim0, -1)].
 */
fun duplicateInterleave(m: FloatArray): FloatArray {
    val result = FloatArray(m.size * 2)
    for (i in m.indices) {
        result[2 * i] = m[i]
        result[2 * i + 1] = m[i]
    }
    return result
}

/**
 * Apply rotary position embeddings.
 *
 * Mirrors [apply_rotary_pos_emb] from the Python reference.
 * Input semantics:
 *   - [x]:     flat array, shape [batch, seqLen, headDim]
 *   - [sin]:   flat array, shape [seqLen, dimHalf]  (before scaling)
 *   - [cos]:   flat array, shape [seqLen, dimHalf]  (before scaling)
 *   - [scale]: flat array, shape [seqLen, dimHalf]  (position-dependent scale)
 *
 * Internally:
 *   1. Scale sin ← sin * scale, cos ← cos * scale  (element-wise)
 *   2. Duplicate interleave each → [seqLen, headDim]
 *   3. result = x * cos + rotateEveryTwo(x) * sin  (with batch broadcasting)
 */
fun applyRotaryPosEmb(
    x: FloatArray,
    sin: FloatArray,
    cos: FloatArray,
    scale: FloatArray,
    batch: Int,
    seqLen: Int,
    headDim: Int
): FloatArray {
    // 1. Scale sin and cos (element-wise, same size)
    val dimHalf = headDim / 2
    require(sin.size == seqLen * dimHalf) {
        "sin.size=${sin.size} != seqLen($seqLen) * dimHalf($dimHalf)"
    }
    require(cos.size == seqLen * dimHalf) {
        "cos.size=${cos.size} != seqLen($seqLen) * dimHalf($dimHalf)"
    }
    require(scale.size == seqLen * dimHalf) {
        "scale.size=${scale.size} != seqLen($seqLen) * dimHalf($dimHalf)"
    }
    require(x.size == batch * seqLen * headDim) {
        "x.size=${x.size} != batch($batch) * seqLen($seqLen) * headDim($headDim)"
    }

    // 2. Duplicate interleave → [seqLen, headDim]
    val scaledSin = duplicateInterleave(mul(sin, scale))
    val scaledCos = duplicateInterleave(mul(cos, scale))

    // 3. Rotate every two on x
    val rotated = rotateEveryTwo(x)

    // 4. result = x * cos + rotated * sin  (broadcast batch)
    val result = FloatArray(x.size)
    val stride = seqLen * headDim
    for (b in 0 until batch) {
        val base = b * stride
        for (s in 0 until seqLen) {
            for (d in 0 until headDim) {
                val xi = base + s * headDim + d
                val si = s * headDim + d
                result[xi] = x[xi] * scaledCos[si] + rotated[xi] * scaledSin[si]
            }
        }
    }
    return result
}

/**
 * Element-wise multiplication of two same-sized arrays.
 */
internal fun mul(a: FloatArray, b: FloatArray): FloatArray {
    require(a.size == b.size) { "Array sizes must match: ${a.size} vs ${b.size}" }
    return FloatArray(a.size) { a[it] * b[it] }
}

// ── XposEncoder ───────────────────────────────────────────────────────

/**
 * XPOS rotary position encoder.
 *
 * Ported from the [XPOS] nn.Module in the Python reference.
 *
 * @param headDim   model dimension (e.g. 320 for the 48px encoder head)
 * @param scaleBase base for the exponential scaling (default 512)
 */
class XposEncoder(val headDim: Int, val scaleBase: Int = 512) {

    /** Precomputed scale buffer for even dimensions:
     *  scale[i] = (2*i + 0.4 * headDim) / (1.4 * headDim),  i = 0 .. headDim/2-1
     */
    val scale: FloatArray = run {
        val n = headDim / 2
        val denom = 1.4f * headDim
        FloatArray(n) { i ->
            ((2 * i).toFloat() + 0.4f * headDim) / denom
        }
    }

    /**
     * Apply XPOS to the input.
     *
     * @param x         flat input array, shape [batch, seqLen, headDim]
     * @param offset    position offset for the encoding (default 0)
     * @param downscale if true, invert the positional scale
     * @param batch     batch dimension (default 1)
     * @param seqLen    sequence length, derived from [x.size] if not provided
     * @return encoded array of the same size as [x]
     */
    fun forward(
        x: FloatArray,
        offset: Int = 0,
        downscale: Boolean = false,
        batch: Int = 1,
        seqLen: Int = x.size / (batch * headDim)
    ): FloatArray {
        val totalSize = batch * seqLen * headDim
        require(x.size == totalSize) {
            "x.size (${x.size}) does not equal " +
                "batch ($batch) * seqLen ($seqLen) * headDim ($headDim) = $totalSize"
        }

        val dimHalf = headDim / 2

        // Position range
        val posCount = seqLen + offset
        val minPos = -(posCount / 2) - if (posCount % 2 == 0) 0 else 1  // Python: -(posCount) // 2
        val maxPosValue = posCount + minPos

        // Exponents for each position: p / scaleBase
        // shape: [posCount]
        val exponents = FloatArray(posCount) { idx ->
            (minPos + idx).toFloat() / scaleBase
        }

        // Position-dependent scale: scale ** (p / scaleBase) for each (p, d)
        // shape: [posCount, dimHalf]
        val posScale = FloatArray(posCount * dimHalf) { idx ->
            val posIdx = idx / dimHalf
            val dimIdx = idx % dimHalf
            scale[dimIdx].pow(exponents[posIdx])
        }

        // Fixed position embeddings from the scale matrix
        // sin, cos shape: [posCount, dimHalf]
        var (sinArr, cosArr) = fixedPosEmbedding(posCount, dimHalf)

        // Trim if we generated more positions than needed
        var finalScale: FloatArray = posScale
        if (posCount > seqLen) {
            val trimStart = posCount - seqLen
            finalScale = posScale.copyOfRange(
                trimStart * dimHalf,
                (trimStart + seqLen) * dimHalf
            )
            sinArr = sinArr.copyOfRange(
                trimStart * dimHalf,
                (trimStart + seqLen) * dimHalf
            )
            cosArr = cosArr.copyOfRange(
                trimStart * dimHalf,
                (trimStart + seqLen) * dimHalf
            )
        }

        // Optionally invert the scale
        if (downscale) {
            finalScale = FloatArray(finalScale.size) { 1f / finalScale[it] }
        }

        return applyRotaryPosEmb(x, sinArr, cosArr, finalScale, batch, seqLen, headDim)
    }
}


